package com.musealgo.oj.problem.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用例文件与事务边界的关系：文件系统没有事务，删除时机必须由事务结果决定。
 * <p>
 * 这里用手工注册的 TransactionSynchronization 模拟提交/回滚两种结局，
 * 断言「提交前旧文件必在、回滚后旧文件仍在且新文件被清理」这一顺序契约。
 * 真实数据库事务下的行为由 {@code ProblemCaseConsistencyIT} 验证。
 * </p>
 */
class TestCaseStorageServiceTest {

    @TempDir
    Path storageRoot;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** 手工开启事务同步（测试里用 completeTransaction 手动触发提交/回滚）。 */
    private void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
    }

    private void completeTransaction(int status) {
        List<TransactionSynchronization> synchronizations =
                new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
        for (TransactionSynchronization synchronization : synchronizations) {
            synchronization.beforeCompletion();
        }
        for (TransactionSynchronization synchronization : synchronizations) {
            if (status == TransactionSynchronization.STATUS_COMMITTED) {
                synchronization.afterCommit();
            }
            synchronization.afterCompletion(status);
        }
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void writesAndReadsImportedCaseFilesInAConfiguredWritableDirectory() {
        TestCaseStorageService storage = new TestCaseStorageService(storageRoot.toString());

        String inputPath = storage.writeInput(42L, 99L, "1 2\n");
        String outputPath = storage.writeOutput(42L, 99L, "3\n");

        assertThat(inputPath).isEqualTo("42/99/input.txt");
        assertThat(outputPath).isEqualTo("42/99/output.txt");
        assertThat(storage.readByRelativePath(inputPath)).isEqualTo("1 2\n");
        assertThat(storage.readByRelativePath(outputPath)).isEqualTo("3\n");
    }

    @Test
    void oldCaseFilesSurviveUntilCommitAndAreRemovedAfterwards() {
        TestCaseStorageService storage = new TestCaseStorageService(storageRoot.toString());
        storage.writeInput(7L, 1L, "old-in");

        beginTransaction();
        storage.deleteCaseDirsAfterCommit(7L, List.of(1L));

        // 事务未提交：旧用例文件必须还在，否则回滚后数据库记录就指向了不存在的文件
        assertThat(storageRoot.resolve("7/1/input.txt")).exists();

        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(storageRoot.resolve("7/1")).doesNotExist();
    }

    @Test
    void rollbackRemovesOnlyFilesCreatedInThisTransaction() {
        TestCaseStorageService storage = new TestCaseStorageService(storageRoot.toString());
        storage.writeInput(7L, 1L, "old-in");

        beginTransaction();
        Set<Long> created = new HashSet<>();
        storage.deleteCaseDirsOnRollback(7L, created);
        // 模拟写入新用例：先登记 ID 再写文件
        created.add(2L);
        storage.writeInput(7L, 2L, "new-in");

        assertThat(storageRoot.resolve("7/2/input.txt")).exists();

        completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        // 新文件被清理，旧文件未受牵连
        assertThat(storageRoot.resolve("7/2")).doesNotExist();
        assertThat(storage.readByRelativePath("7/1/input.txt")).isEqualTo("old-in");
    }

    @Test
    void committedInsertKeepsNewFiles() {
        TestCaseStorageService storage = new TestCaseStorageService(storageRoot.toString());

        beginTransaction();
        Set<Long> created = new HashSet<>();
        storage.deleteCaseDirsOnRollback(7L, created);
        created.add(3L);
        storage.writeInput(7L, 3L, "new-in");

        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(storage.readByRelativePath("7/3/input.txt")).isEqualTo("new-in");
    }

    @Test
    void deleteWithoutActiveTransactionIsImmediate() {
        TestCaseStorageService storage = new TestCaseStorageService(storageRoot.toString());
        Path file = storageRoot.resolve("8/1/input.txt");
        storage.writeInput(8L, 1L, "x");

        storage.deleteCaseDirsAfterCommit(8L, List.of(1L));

        assertThat(Files.exists(file)).isFalse();
    }
}
