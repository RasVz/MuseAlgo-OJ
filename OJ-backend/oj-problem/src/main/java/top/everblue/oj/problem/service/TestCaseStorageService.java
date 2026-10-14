package top.everblue.oj.problem.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * 测试用例文件存储服务。
 * <p>
 * 负责将判题用例的输入/输出内容写入本地文件系统，以及从文件系统读取。
 * 路径规则：{basePath}/{problemId}/{caseId}/input.txt (output.txt)
 * <p>
 * <b>为什么不把测试数据存数据库：</b>测试数据是大段纯文本且按整块读，
 * 放数据库会让题目查询频繁带出大字段、拖慢列表页；放文件系统后题目表只留元信息
 * （字节数、分组、顺序），正文由判题时按需读取。
 * <b>代价：</b>数据库与文件系统没有事务，删除题目时必须显式两步清理
 * （先删库再删文件），否则会留下孤儿文件。
 * </p>
 * <p>
 * <b>数据库只存相对路径</b>（形如 {@code 42/156/input.txt}），不存绝对路径。
 * 这样换机器、换存储根目录时历史数据仍然可用，也便于整体迁移。
 * </p>
 * <p>
 * 配置：通过 {@code oj.testcase.base-path} 指定根目录，默认 ./data/testcases。
 */
@Slf4j
@Service
public class TestCaseStorageService {

    private final Path basePath;

    public TestCaseStorageService(@Value("${oj.testcase.base-path:./data/testcases}") String basePath) {
        this.basePath = Paths.get(basePath).toAbsolutePath().normalize();
        log.info("测试用例存储路径: {}", this.basePath);
    }

    /** 构建指定用例的目录路径 */
    public Path caseDir(Long problemId, Long caseId) {
        return basePath
                .resolve(String.valueOf(Objects.requireNonNull(problemId, "problemId")))
                .resolve(String.valueOf(Objects.requireNonNull(caseId, "caseId")));
    }

    /** 输入文件路径 */
    public Path inputFile(Long problemId, Long caseId) {
        return caseDir(problemId, caseId).resolve("input.txt");
    }

    /** 输出文件路径 */
    public Path outputFile(Long problemId, Long caseId) {
        return caseDir(problemId, caseId).resolve("output.txt");
    }

    /**
     * 写入测试用例到文件系统。
     *
     * @return 相对路径（如 "42/156/input.txt"），用于存入数据库
     */
    public String writeInput(Long problemId, Long caseId, String content) {
        return write(inputFile(problemId, caseId), content);
    }

    public String writeOutput(Long problemId, Long caseId, String content) {
        return write(outputFile(problemId, caseId), content);
    }

    private String write(Path filePath, String content) {
        try {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, content, StandardCharsets.UTF_8);
            return basePath.relativize(filePath).toString();
        } catch (IOException e) {
            throw new RuntimeException("写入测试用例文件失败: " + filePath, e);
        }
    }

    /** 从文件系统读取测试用例内容 */
    public String readContent(Path filePath) {
        try {
            return Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("读取测试用例文件失败: " + filePath, e);
        }
    }

    /**
     * 根据数据库中存储的相对路径读取内容（含路径穿越防护）
     * <p>
     * <b>路径穿越防护不可删除。</b>相对路径来自数据库，理论上可被篡改为
     * {@code ../../../etc/passwd}；{@code normalize()} 之后 {@code resolve}
     * 会正常返回根目录外的真实路径，因此必须用 {@code startsWith} 卡住。
     * 去掉这个判断就等于开放任意文件读取。
     * </p>
     *
     * @param relativePath 库中存的相对路径
     * @return 文件内容；路径为空时返回 null
     * @throws SecurityException 路径逃逸出存储根目录
     */
    public String readByRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        Path resolved = basePath.resolve(relativePath).normalize();
        if (!resolved.startsWith(basePath)) {
            throw new SecurityException("非法的测试用例路径: " + relativePath);
        }
        return readContent(resolved);
    }

    /**
     * 删除某个题目的所有测试用例文件
     * <p>
     * 递归删除时按<b>路径倒序</b>（{@code reverseOrder}）遍历，保证先删文件再删目录；
     * 若正序删除，目录非空会导致删除失败。
     * 单个条目删除失败只记 WARN 不中断：DB 记录已清掉，残留文件不影响判题，
     * 因一个删不掉的文件让整个删除流程抛异常反而更糟。
     * </p>
     */
    public void deleteByProblemId(Long problemId) {
        Path dir = basePath.resolve(String.valueOf(problemId));
        try {
            if (Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(java.util.Comparator.reverseOrder())
                          .forEach(p -> {
                              try { Files.deleteIfExists(p); }
                              catch (IOException ignored) { }
                          });
                }
                log.info("已删除题目 {} 的测试用例文件: {}", problemId, dir);
            }
        } catch (IOException e) {
            log.warn("删除测试用例文件失败 problemId={}: {}", problemId, e.getMessage());
        }
    }

    /** 删除单个用例文件 */
    public void deleteCase(Long problemId, Long caseId) {
        Path dir = caseDir(problemId, caseId);
        try {
            if (Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(java.util.Comparator.reverseOrder())
                          .forEach(p -> {
                              try { Files.deleteIfExists(p); }
                              catch (IOException ignored) { }
                          });
                }
            }
        } catch (IOException e) {
            log.warn("删除测试用例文件失败 caseId={}: {}", caseId, e.getMessage());
        }
    }
}
