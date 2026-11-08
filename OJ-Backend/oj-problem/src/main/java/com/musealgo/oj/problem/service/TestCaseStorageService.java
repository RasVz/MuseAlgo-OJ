package com.musealgo.oj.problem.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 测试用例文件存储服务。
 * <p>
 * 负责将判题用例的输入/输出内容写入本地文件系统，以及从文件系统读取。
 * 路径规则：{basePath}/{problemId}/{caseId}/input.txt (output.txt)
 * <p>
 * <b>为什么不把测试数据存数据库：</b>测试数据是大段纯文本且按整块读，
 * 放数据库会让题目查询频繁带出大字段、拖慢列表页；放文件系统后题目表只留元信息
 * （字节数、分组、顺序），正文由判题时按需读取。
 * <b>代价：</b>数据库与文件系统没有事务。因此所有「覆盖/删除」路径都必须遵守同一条顺序：
 * <b>先写新文件 → 提交数据库 → 提交后再删旧文件</b>（见 {@link #deleteCaseDirsAfterCommit}），
 * 中途失败则回滚数据库并删除本次新建的文件（见 {@link #deleteCaseDirsOnRollback}）。
 * 反过来「先删文件再写库」会在事务回滚后留下「数据库记录还在、文件已丢」的坏数据——
 * 数据库能回滚，文件系统不能。
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

    /** 批量删除指定用例目录（逐个删除，单条失败只记 WARN）。 */
    public void deleteCaseDirs(Long problemId, Collection<Long> caseIds) {
        if (problemId == null || caseIds == null) {
            return;
        }
        for (Long caseId : caseIds) {
            if (caseId != null) {
                deleteCase(problemId, caseId);
            }
        }
    }

    /**
     * 事务提交后再删除这些用例目录。
     * <p>
     * 用例覆盖写入的顺序必须是「先写新文件 → 提交数据库 → 再删旧文件」：
     * 数据库能回滚而文件系统不能，先删旧文件会在事务回滚后留下「旧数据库记录 + 文件已丢」的
     * 半损状态。提交后删除保证任何回滚都不会动到旧用例文件，最坏只留下孤儿文件
     * （不影响判题，可按题清理）。无事务时立即删除。
     * </p>
     */
    public void deleteCaseDirsAfterCommit(Long problemId, Collection<Long> caseIds) {
        if (problemId == null || caseIds == null || caseIds.isEmpty()) {
            return;
        }
        List<Long> snapshot = new ArrayList<>(caseIds);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteCaseDirs(problemId, snapshot);
                }
            });
        } else {
            deleteCaseDirs(problemId, snapshot);
        }
    }

    /**
     * 事务未提交（回滚或提交失败）时删除本次新建的用例目录，避免孤儿文件。
     * <p>
     * 传入的集合在事务执行过程中会被持续填充（每插入一条用例就登记其 ID），
     * 因此中途失败也能清理到已经写出的那部分文件；登记必须发生在写文件之前。
     * </p>
     */
    public void deleteCaseDirsOnRollback(Long problemId, Set<Long> createdCaseIds) {
        if (problemId == null || createdCaseIds == null
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    deleteCaseDirs(problemId, new ArrayList<>(createdCaseIds));
                }
            }
        });
    }

    /** 事务提交后删除整题的用例目录（删除题目时用，回滚不得动文件）。 */
    public void deleteProblemDirsAfterCommit(Long problemId) {
        if (problemId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteByProblemId(problemId);
                }
            });
        } else {
            deleteByProblemId(problemId);
        }
    }
}
