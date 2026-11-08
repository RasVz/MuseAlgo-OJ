package com.musealgo.oj.problem.service.impl;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.problem.error.ProblemErrorCode;

import com.musealgo.oj.problem.application.ProblemCaseApplicationService;
import com.musealgo.oj.problem.entity.ProblemCase;
import com.musealgo.oj.problem.mapper.ProblemCaseMapper;
import com.musealgo.oj.problem.service.ProblemCaseService;
import com.musealgo.oj.problem.service.TestCaseStorageService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import cn.hutool.core.io.FileUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.Comparator;

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
    private static final int MAX_ZIP_ENTRIES = 10_000;

    /**
     * 用例文件名排序：纯数字前缀按数值比较（1.in < 2.in < 10.in），
     * 其余按字典序。避免 HashMap 迭代顺序让 case_order 与分值分配每次都不一样。
     */
    private static final Comparator<String> CASE_KEY_COMPARATOR = (left, right) -> {
        Long leftNumber = numericKey(left);
        Long rightNumber = numericKey(right);
        if (leftNumber != null && rightNumber != null && !leftNumber.equals(rightNumber)) {
            return Long.compare(leftNumber, rightNumber);
        }
        if (leftNumber != null && rightNumber == null) {
            return -1;
        }
        if (leftNumber == null && rightNumber != null) {
            return 1;
        }
        return left.compareTo(right);
    };

    private static Long numericKey(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        for (int i = 0; i < key.length(); i++) {
            if (!Character.isDigit(key.charAt(i))) {
                return null;
            }
        }
        try {
            return Long.parseLong(key);
        } catch (NumberFormatException e) {
            return null;
        }
    }

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
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "上传的文件为空");
        }

        // 2. 创建临时目录存放解压文件
        File tempZip = null;
        File unzipDir = null;
        try {
            // 创建临时 zip 文件
            tempZip = File.createTempFile("oj_upload_", ".zip");
            file.transferTo(tempZip);

            // 创建解压目录（原子操作，避免 TOCTOU 竞态）
            unzipDir = Files.createTempDirectory("oj_unzip_").toFile();

            // 路径、条目数和累计字节数在落盘前检查；不先完整解压再检查。
            BoundedCaseZipExtractor.extract(tempZip.toPath(), unzipDir.toPath(),
                    MAX_UNZIPPED_BYTES, MAX_ZIP_ENTRIES);

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

            // 5. 先配对并校验，再清理旧数据：
            //    校验失败时直接中止，不删已有用例（否则一次错误上传就会丢光该题测试数据）。
            List<String> caseKeys = new ArrayList<>(inFiles.keySet());
            caseKeys.sort(CASE_KEY_COMPARATOR);
            List<String> matchedKeys = new ArrayList<>();
            for (String key : caseKeys) {
                if (outFiles.containsKey(key)) {
                    matchedKeys.add(key);
                }
            }
            if (matchedKeys.isEmpty()) {
                throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "ZIP 中未找到成对的 .in/.out（或 .ans）用例文件，已保留原有测试用例");
            }
            if (matchedKeys.size() > ProblemCaseApplicationService.MAX_CASES_PER_PROBLEM) {
                throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "单题测试用例上限 "
                        + ProblemCaseApplicationService.MAX_CASES_PER_PROBLEM + " 组，本次上传 "
                        + matchedKeys.size() + " 组，已保留原有测试用例，请拆分后重试");
            }

            // 5a. 本次新建用例的 ID 逐个登记：事务未提交时据此删除已写出的文件。
            //     登记必须发生在写文件之前，否则写文件阶段抛异常就清理不到这一条。
            Set<Long> createdCaseIds = new HashSet<>();
            testCaseStorageService.deleteCaseDirsOnRollback(problemId, createdCaseIds);

            // 6. 只清理旧的数据库记录，旧文件保留到事务提交之后：
            //    数据库可以回滚而文件不能，先删文件会让「导入失败回滚」变成「旧用例文件永久丢失」。
            QueryWrapper<ProblemCase> wrapper = new QueryWrapper<>();
            wrapper.eq("problem_id", problemId);
            problemCaseMapper.delete(wrapper);

            // 7. 按文件名顺序落库：普通用例统一按 100 分等分（与 JSON 覆盖保存语义一致），
            //    此前固定 10 分会导致「5 组用例满分只有 50」这类不一致。
            int caseOrder = 1;
            for (String key : matchedKeys) {
                String inputContent = FileUtil.readString(inFiles.get(key), StandardCharsets.UTF_8).trim();
                String outputContent = FileUtil.readString(outFiles.get(key), StandardCharsets.UTF_8).trim();

                ProblemCase problemCase = new ProblemCase();
                problemCase.setProblemId(problemId);
                problemCase.setScore(ProblemCaseApplicationService.importedCaseScore(
                        caseOrder - 1, matchedKeys.size()));
                problemCase.setStatus((byte) 1);
                problemCase.setCaseType((byte) 0);
                problemCase.setCaseOrder(caseOrder++);

                problemCaseMapper.insert(problemCase);
                createdCaseIds.add(problemCase.getId());

                // 写出到文件系统，回填路径
                String inputPath = testCaseStorageService.writeInput(problemId, problemCase.getId(), inputContent);
                String outputPath = testCaseStorageService.writeOutput(problemId, problemCase.getId(), outputContent);

                problemCase.setInputFilePath(inputPath);
                problemCase.setOutputFilePath(outputPath);
                problemCaseMapper.updateById(problemCase);
            }

            return matchedKeys.size();

        } catch (BusinessException e) {
            throw e;
        } catch (IllegalArgumentException invalidArchive) {
            // The bounded archive parser emits controlled validation messages before old cases are cleared.
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, invalidArchive.getMessage());
        } catch (Exception e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.util.zip.ZipException) {
                    throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "压缩包格式无效或内容损坏");
                }
            }
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

    /**
     * {@inheritDoc}
     * <p>
     * {@code FOR SHARE} 是锁定读：在 REPEATABLE READ 下不返回事务开始时的快照，
     * 而是返回最新的已提交版本，供「标准答案保存前」做最后一次一致性比对。
     * </p>
     */
    @Override
    public List<ProblemCase> getProblemCasesForShare(Long problemId) {
        QueryWrapper<ProblemCase> wrapper = new QueryWrapper<>();
        wrapper.eq("problem_id", problemId);
        wrapper.orderByAsc("case_order", "id");
        wrapper.last("FOR SHARE");
        return this.list(wrapper);
    }
}
