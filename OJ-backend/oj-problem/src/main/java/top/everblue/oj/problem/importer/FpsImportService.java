package top.everblue.oj.problem.importer;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import top.everblue.oj.problem.application.ProblemManagementApplicationService;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * FPS 题集文件的批量导入入口。
 * <p>
 * 职责边界：只做「读取上传文件 → 解析 → 逐题落库 → 汇总结果」这条流水线，
 * 不做权限判定（由下游 {@link ProblemManagementApplicationService} 负责），
 * 也不做题目编辑（导入后的题目一律为草稿私有态）。
 * </p>
 * <p>
 * <b>容错策略：题目级隔离。</b>单道题解析失败或落库失败，都只把该行记为失败并继续处理下一道，
 * 最终把逐题结果交给前端展示。因此这里<b>不</b>对单题抛异常；只有整个文件级别的
 * 问题（空文件、未登录、ZIP 结构非法、超过总量上限）才抛 {@link IllegalArgumentException}。
 * </p>
 * <p>
 * <b>幂等性：本导入不保证幂等。</b>每次调用都会新建题目行，不按标题或来源做去重——
 * 重复上传会得到重复题目。这是有意为之：FPS 文件里的标题并不唯一，
 * 按标题去重会误删两道本来不同的题，误删比重复更难挽回。真正的去重
 * 需要业务侧提供题号（PID）等可靠唯一键，导入格式目前不含该字段。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class FpsImportService {

    private static final int MAX_ZIP_ENTRIES = 1000;
    private static final int MAX_ARCHIVE_ENTRIES = 100;
    private static final long MAX_XML_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_ARCHIVE_BYTES = 128L * 1024L * 1024L;

    private final FpsProblemSetParser parser;
    private final ProblemManagementApplicationService problemManagement;

    /**
     * 导入一个 FPS 文件（裸 XML 或 ZIP 均可）。
     *
     * @param file    上传文件，不能为 null 或空
     * @param actorId 操作者 ID，不能为 null（题目归属该用户）
     * @return 逐题导入结果与汇总计数；{@code total == succeeded + failed}
     * @throws IllegalArgumentException 文件为空、未登录，或文件级校验（ZIP 条目数、解压总量）不通过
     */
    public FpsImportResult importFile(MultipartFile file, Long actorId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("FPS 文件为空");
        }
        if (actorId == null) {
            throw new IllegalArgumentException("请先登录");
        }

        List<FpsParsedItem> parsedItems;
        try {
            parsedItems = parseUpload(file);
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取 FPS 文件: " + e.getMessage(), e);
        }

        List<FpsImportItemResult> items = new ArrayList<>(parsedItems.size());
        int succeeded = 0;
        for (FpsParsedItem parsedItem : parsedItems) {
            if (!parsedItem.success()) {
                items.add(FpsImportItemResult.failure(parsedItem.position(), parsedItem.title(), parsedItem.error()));
                continue;
            }
            FpsProblem problem = parsedItem.problem();
            try {
                Long problemId = problemManagement.createFromFps(problem, actorId);
                String message = problem.warnings().isEmpty()
                        ? "导入成功"
                        : "导入成功；" + String.join("；", problem.warnings());
                items.add(FpsImportItemResult.success(parsedItem.position(), problem.title(), problemId,
                        problem.testCases().size(), message));
                succeeded++;
            } catch (Exception e) {
                items.add(FpsImportItemResult.failure(parsedItem.position(), problem.title(), rootMessage(e)));
            }
        }
        return new FpsImportResult(parsedItems.size(), succeeded, parsedItems.size() - succeeded, List.copyOf(items));
    }

    /**
     * 探测魔数后决定走 ZIP 还是裸 XML 分支。
     * <p>
     * 用 {@link PushbackInputStream} 把已读的 4 字节魔数推回流里，而不是包一层
     * {@code BufferedInputStream}：推回后解析器仍能直接拿到原始流，
     * 避免为了嗅探格式而多一层无谓的缓冲拷贝。
     * </p>
     */
    private List<FpsParsedItem> parseUpload(MultipartFile file) throws IOException {
        try (PushbackInputStream input = new PushbackInputStream(file.getInputStream(), 4)) {
            byte[] signature = input.readNBytes(4);
            input.unread(signature);
            return isZip(signature) ? parseZip(input) : parser.parse(input);
        }
    }

    /**
     * 解析 ZIP 容器，逐个取出 {@code .xml} / {@code .fps} 条目解析。
     * <p>
     * <b>字符集固定 GBK：</b>Windows 版 FPS 打包工具生成的 ZIP 条目名不带 UTF-8 标志位，
     * JDK 默认按 UTF-8 解码会把中文文件名解成乱码，导致条目被误判为非 FPS 文件而整体漏导入。
     * </p>
     * <b>防解压炸弹：</b>同时限制条目数、单条目解压后大小、以及所有条目解压总量。
     * 非 FPS 条目也要读出来计费（{@link #discardLimited}）——只统计目标文件的话，
     * 一个塞满无关大文件的压缩包可以绕过总量限制。
     * </p>
     */
    private List<FpsParsedItem> parseZip(InputStream input) throws IOException {
        List<FpsParsedItem> parsedItems = new ArrayList<>();
        int entryCount = 0;
        int xmlEntryCount = 0;
        long totalBytes = 0;
        try (ZipInputStream zip = new ZipInputStream(input, Charset.forName("GBK"))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                entryCount++;
                if (entryCount > MAX_ZIP_ENTRIES) {
                    throw new IllegalArgumentException("ZIP 中最多支持 " + MAX_ZIP_ENTRIES + " 个条目");
                }
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                if (!isFpsEntry(entry.getName())) {
                    totalBytes += discardLimited(zip, MAX_ARCHIVE_BYTES - totalBytes);
                    zip.closeEntry();
                    continue;
                }
                xmlEntryCount++;
                if (xmlEntryCount > MAX_ARCHIVE_ENTRIES) {
                    throw new IllegalArgumentException("ZIP 中最多支持 " + MAX_ARCHIVE_ENTRIES + " 个 FPS/XML 文件");
                }

                long remainingBytes = MAX_ARCHIVE_BYTES - totalBytes;
                String limitMessage = remainingBytes < MAX_XML_BYTES
                        ? "ZIP 解压后的所有文件总大小超过 128 MB"
                        : "ZIP 内单个 FPS/XML 文件超过 128 MB";
                byte[] xml = readLimited(zip, Math.min(MAX_XML_BYTES, remainingBytes), limitMessage);
                totalBytes += xml.length;
                try {
                    appendWithGlobalPositions(parsedItems, parser.parse(new ByteArrayInputStream(xml)));
                } catch (IllegalArgumentException e) {
                    addParsedItem(parsedItems, FpsParsedItem.failure(
                            parsedItems.size() + 1, entry.getName(), e.getMessage()));
                }
                zip.closeEntry();
            }
        }
        if (xmlEntryCount == 0) {
            throw new IllegalArgumentException("ZIP 中没有 .xml 或 .fps 文件");
        }
        return List.copyOf(parsedItems);
    }

    private long discardLimited(InputStream input, long limit) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        for (int read; (read = input.read(buffer)) != -1; ) {
            total += read;
            if (total > limit) {
                throw new IllegalArgumentException("ZIP 解压后的所有文件总大小超过 128 MB");
            }
        }
        return total;
    }

    private byte[] readLimited(InputStream input, long limit, String errorMessage) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        for (int read; (read = input.read(buffer)) != -1; ) {
            total += read;
            if (total > limit) throw new IllegalArgumentException(errorMessage);
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    /**
     * 把某个 ZIP 条目的解析结果并入全局结果，并把序号改写为跨文件的全局位置。
     * <p>
     * 前端展示导入结果时按 {@code position} 排序，若沿用「文件内序号」，
     * 多文件 ZIP 里每份都会从 1 开始，用户无法判断是哪一道题。
     * </p>
     */
    private void appendWithGlobalPositions(List<FpsParsedItem> target, List<FpsParsedItem> source) {
        for (FpsParsedItem item : source) {
            int position = target.size() + 1;
            addParsedItem(target, item.success()
                    ? FpsParsedItem.success(position, item.problem())
                    : FpsParsedItem.failure(position, item.title(), item.error()));
        }
    }

    private void addParsedItem(List<FpsParsedItem> target, FpsParsedItem item) {
        if (target.size() >= FpsProblemSetParser.MAX_PROBLEMS) {
            throw new IllegalArgumentException("单次最多导入 " + FpsProblemSetParser.MAX_PROBLEMS + " 道题目");
        }
        target.add(item);
    }

    private boolean isZip(byte[] signature) {
        return signature.length == 4 && signature[0] == 'P' && signature[1] == 'K'
                && ((signature[2] == 3 && signature[3] == 4)
                || (signature[2] == 5 && signature[3] == 6)
                || (signature[2] == 7 && signature[3] == 8));
    }

    private boolean isFpsEntry(String name) {
        String lowerName = name.toLowerCase(Locale.ROOT);
        return lowerName.endsWith(".xml") || lowerName.endsWith(".fps");
    }

    /**
     * 取最内层异常信息作为失败原因。
     * <p>
     * 一路下钻到根因而不是直接用外层 {@code getMessage()}：业务异常常被包在
     * {@code RuntimeException} 里，外层 message 往往是 null 或泛化文案，
     * 展示给用户没有诊断价值。
     * </p>
     */
    private String rootMessage(Exception exception) {
        Throwable root = exception;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null || root.getMessage().isBlank() ? "导入失败" : root.getMessage();
    }
}
