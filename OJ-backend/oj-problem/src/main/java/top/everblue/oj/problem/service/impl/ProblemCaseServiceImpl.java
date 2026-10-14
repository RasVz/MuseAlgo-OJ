package top.everblue.oj.problem.service.impl;

import top.everblue.oj.problem.entity.ProblemCase;
import top.everblue.oj.problem.mapper.ProblemCaseMapper;
import top.everblue.oj.problem.service.ProblemCaseService;
import top.everblue.oj.problem.service.TestCaseStorageService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ZipUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * <p>
 * 判题用例表 服务实现类
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
@Service
@RequiredArgsConstructor
public class ProblemCaseServiceImpl extends ServiceImpl<ProblemCaseMapper, ProblemCase> implements ProblemCaseService {
    private final ProblemCaseMapper problemCaseMapper;
    private final TestCaseStorageService testCaseStorageService;

    private static final long MAX_UNZIPPED_BYTES = 100 * 1024 * 1024; // 100 MB

    private String normalizeCaseFileName(File file) {
        if (file == null) {
            return null;
        }

        String name = file.getName();
        if (name == null) {
            return null;
        }

        name = name.trim();
        if (name.isEmpty() || name.startsWith(".") || name.startsWith("~")) {
            return null;
        }

        return name;
    }

    private String extractCaseKey(String fileName, String suffix) {
        if (fileName == null) {
            return null;
        }

        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(suffix)) {
            return null;
        }

        return fileName.substring(0, fileName.length() - suffix.length()).trim();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int importCasesFromZip(MultipartFile file, Long problemId) {
        // 1. 检查文件是否为空
        if (file.isEmpty()) {
            throw new RuntimeException("上传的文件为空");
        }

        boolean oldFilesCleared = false;
        // 2. 创建临时目录存放解压文件
        File tempZip = null;
        File unzipDir = null;
        try {
            // 创建临时 zip 文件
            tempZip = File.createTempFile("oj_upload_", ".zip");
            file.transferTo(tempZip);

            // 创建解压目录（原子操作，避免 TOCTOU 竞态）
            unzipDir = Files.createTempDirectory("oj_unzip_").toFile();

            // 3. 使用 Hutool 解压
            ZipUtil.unzip(tempZip, unzipDir);

            // 3a. Zip Slip + ZIP 炸弹防护
            Path unzipPath = unzipDir.toPath().toRealPath();
            long totalBytes = 0;
            for (File f : FileUtil.loopFiles(unzipDir)) {
                // Zip Slip: Path.startsWith 防 "/tmp/unzip" 匹配 "/tmp/unzip_evil" 绕过
                if (!f.toPath().toRealPath().startsWith(unzipPath)) {
                    throw new SecurityException("ZIP 包含非法路径: " + f.getName());
                }
                totalBytes += f.length();
                if (totalBytes > MAX_UNZIPPED_BYTES) {
                    throw new RuntimeException("ZIP 解压后超过 " + (MAX_UNZIPPED_BYTES / 1024 / 1024) + " MB 限制");
                }
            }

            // 4. 读取解压后的所有文件
            List<File> files = FileUtil.loopFiles(unzipDir);

            // 分类存放 .in 和 .out 文件
            // Key: 文件名前缀 (比如 "1.in" -> Key是 "1")
            Map<String, File> inFiles = new HashMap<>();
            Map<String, File> outFiles = new HashMap<>();

            for (File f : files) {
                String name = normalizeCaseFileName(f);
                if (name == null) {
                    continue;
                }

                String lowerName = name.toLowerCase(Locale.ROOT);
                if (lowerName.endsWith(".in")) {
                    String key = extractCaseKey(name, ".in");
                    if (key != null && !key.isEmpty()) {
                        inFiles.put(key, f);
                    }
                } else if (lowerName.endsWith(".out")) {
                    String key = extractCaseKey(name, ".out");
                    if (key != null && !key.isEmpty()) {
                        outFiles.put(key, f);
                    }
                } else if (lowerName.endsWith(".ans")) { // 支持 .ans 后缀
                    String key = extractCaseKey(name, ".ans");
                    if (key != null && !key.isEmpty()) {
                        outFiles.put(key, f);
                    }
                }
            }

            // 5. 匹配并入库
            // 先清理该题目旧的测试用例文件和数据库记录
            testCaseStorageService.deleteByProblemId(problemId);
            oldFilesCleared = true;
            QueryWrapper<ProblemCase> wrapper = new QueryWrapper<>();
            wrapper.eq("problem_id", problemId);
            problemCaseMapper.delete(wrapper);

            List<ProblemCase> caseList = new ArrayList<>();

            // 遍历所有输入文件，寻找对应的输出文件
            int caseOrder = 1;
            for (String key : inFiles.keySet()) {
                File inFile = inFiles.get(key);
                File outFile = outFiles.get(key);

                if (outFile == null) {
                    continue;
                }

                // 读取内容
                String inputContent = FileUtil.readString(inFile, StandardCharsets.UTF_8).trim();
                String outputContent = FileUtil.readString(outFile, StandardCharsets.UTF_8).trim();

                // 构建实体，先插入获取 caseId
                ProblemCase problemCase = new ProblemCase();
                problemCase.setProblemId(problemId);
                problemCase.setScore(10);
                problemCase.setStatus((byte) 1);
                problemCase.setCaseType((byte) 0);
                problemCase.setCaseOrder(caseOrder++);

                problemCaseMapper.insert(problemCase);

                // 写出到文件系统，回填路径
                String inputPath = testCaseStorageService.writeInput(problemId, problemCase.getId(), inputContent);
                String outputPath = testCaseStorageService.writeOutput(problemId, problemCase.getId(), outputContent);

                problemCase.setInputFilePath(inputPath);
                problemCase.setOutputFilePath(outputPath);
                problemCaseMapper.updateById(problemCase);

                caseList.add(problemCase);
            }

            return caseList.size();

        } catch (Exception e) {
            // 仅在旧文件已清理、新文件部分写入时才需回滚清理
            if (oldFilesCleared) testCaseStorageService.deleteByProblemId(problemId);
            throw new RuntimeException("解析 Zip 文件失败: " + e.getMessage(), e);
        } finally {
            // 7. 清理临时文件
            FileUtil.del(tempZip);
            FileUtil.del(unzipDir);
        }
    }

    // 获取某道题目的所有测试样例
    @Override
    public List<ProblemCase> getProblemCases(Long problemId) {
        QueryWrapper<ProblemCase> wrapper = new QueryWrapper<>();
        // 对应数据库字段 problem_id
        wrapper.eq("problem_id", problemId);
        // 可选：按照ID排序，或者按照你可能有自定义的顺序字段排序
        wrapper.orderByAsc("case_order", "id");

        return this.list(wrapper);
    }
}
