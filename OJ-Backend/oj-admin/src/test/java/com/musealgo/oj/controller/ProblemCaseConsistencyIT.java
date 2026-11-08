package com.musealgo.oj.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.musealgo.oj.OjApplication;
import com.musealgo.oj.problem.application.CaseSetChangedException;
import com.musealgo.oj.problem.application.ProblemCaseApplicationService;
import com.musealgo.oj.problem.application.ProblemStdApplicationService;
import com.musealgo.oj.problem.entity.Problem;
import com.musealgo.oj.problem.importer.FpsTestCase;
import com.musealgo.oj.problem.service.ProblemService;
import com.musealgo.oj.problem.service.TestCaseStorageService;
import com.musealgo.oj.system.entity.LoginUser;
import com.musealgo.oj.system.entity.User;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * 真实 MySQL 下的用例可靠性验收（2026-10-30 修复的受控验证）。
 *
 * <p>覆盖两类此前确认存在的问题：</p>
 * <ol>
 *   <li>标准答案「测试并保存」的指纹比对与落库不是原子操作：验证结束后、保存提交前，
 *       用例可能被另一个窗口改掉。修复后比对与写库在题目行写锁内完成，
 *       用例写入也必须先取同一把锁，因此交错只会出现两种结局——保存被拒绝，
 *       或者保存先提交、随后的用例变更把「已测试」清零。</li>
 *   <li>用例文件与数据库的回滚不一致：先删文件后写库时，事务回滚会让旧记录指向已删除的文件。
 *       修复后先写新文件、提交后才删旧文件、回滚时清理新文件。</li>
 * </ol>
 *
 * <p>本类只造合成数据并在 @AfterEach 清理；用例文件写入测试专属目录（target/oj-case-it-files）。</p>
 */
@SpringBootTest(classes = OjApplication.class, properties = {
        "oj.messaging.rabbitmq-enabled=false",
        "oj.judge.worker.enabled=false",
        "report.worker.enabled=false",
        "report.notification.worker-enabled=false",
        "oj.testcase.base-path=target/oj-case-it-files"
})
class ProblemCaseConsistencyIT {

    @Autowired private ProblemCaseApplicationService caseService;
    @Autowired private ProblemStdApplicationService stdService;
    @Autowired private ProblemService problems;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    /** spy 真实文件存储：用于在受控位置注入写入失败，验证回滚边界 */
    @MockitoSpyBean
    private TestCaseStorageService storage;

    /** spy 用例持久层：用于让重排更新失败，验证删除路径的回滚边界 */
    @MockitoSpyBean
    private com.musealgo.oj.problem.service.ProblemCaseService problemCaseService;

    private final java.util.Set<Long> problemIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<Long> userIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        for (Long problemId : problemIds) {
            List<Long> caseIds = jdbc.queryForList("SELECT id FROM problem_case WHERE problem_id=?", Long.class, problemId);
            jdbc.update("DELETE FROM problem_case WHERE problem_id=?", problemId);
            jdbc.update("DELETE FROM problem WHERE id=?", problemId);
            for (Long caseId : caseIds) {
                // 直接清文件：测试目录本身也是专属目录，双重保险
                storage.deleteCase(problemId, caseId);
            }
        }
        problemIds.clear();
        for (Long userId : userIds) {
            jdbc.update("DELETE FROM user WHERE id=?", userId);
        }
        userIds.clear();
    }

    // ------------------------------------------------------------------
    // 一、标准答案指纹并发窗口
    // ------------------------------------------------------------------

    @Test
    void stdSaveIsRejectedWhenCasesChangedAfterVerification() {
        Long problemId = fixtureWithTwoCases();
        // 模拟 verifyStdAgainstCases 在验证开始时记录的指纹
        String fingerprintAtVerifyStart = caseService.caseFingerprint(problemId);

        // 验证期间（几十秒的 Judge0 过程在此被压缩掉）用例被另一个窗口改写
        caseService.saveCases(problemId, List.of(new FpsTestCase("changed-in", "changed-out")));

        assertThrows(CaseSetChangedException.class,
                () -> stdService.saveStdTested(problemId, "int main(){}", "思路", fingerprintAtVerifyStart));

        assertEquals(0, jdbc.queryForObject("SELECT std_tested FROM problem WHERE id=?", Integer.class, problemId),
                "用例已变化时绝不能把旧验证结果标为已测试");
        assertNull(jdbc.queryForObject("SELECT std_answer FROM problem WHERE id=?", String.class, problemId));
    }

    @Test
    void stdSaveStillSucceedsWhenCasesUnchanged() {
        Long problemId = fixtureWithTwoCases();
        String fingerprint = caseService.caseFingerprint(problemId);

        stdService.saveStdTested(problemId, "int main(){}", "思路", fingerprint);

        assertEquals(1, jdbc.queryForObject("SELECT std_tested FROM problem WHERE id=?", Integer.class, problemId));
        assertEquals("int main(){}", jdbc.queryForObject("SELECT std_answer FROM problem WHERE id=?", String.class, problemId));
    }

    /**
     * 受控交错（本次修复的核心场景）：
     * 线程 A 与「测试并保存」完全同序——取题目行写锁 → 锁定读比对（此时通过）→ 写 std_tested=1 → 提交；
     * 线程 B 在 A 持锁期间尝试改用例。
     * 期望：B 被 A 的行锁挡住，A 提交后 B 才能提交；B 的用例变更在 A 写入之后生效，
     * 最终 std_tested 必须回到 0，绝不能留下「已测试」盖在新用例上。
     */
    @Test
    void caseChangeCommittedAfterStdSaveCannotLeaveStdTested() throws Exception {
        Long problemId = fixtureWithTwoCases();
        String fingerprintAtVerifyStart = caseService.caseFingerprint(problemId);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        // 线程池没有主线程的登录态，先取一份快照（SecurityContext 是 ThreadLocal）
        var securityContext = SecurityContextHolder.getContext();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch saveLocked = new CountDownLatch(1);
            CountDownLatch changeAttempted = new CountDownLatch(1);
            AtomicReference<Instant> saveCommittedAt = new AtomicReference<>();
            AtomicReference<Instant> changeCommittedAt = new AtomicReference<>();

            Future<?> saveFuture = pool.submit(() -> {
                // SecurityContext 是 ThreadLocal，线程池里要显式带上登录态
                SecurityContextHolder.setContext(securityContext);
                try {
                    tx.executeWithoutResult(status -> {
                        // 与 ProblemStdApplicationService.saveStdTested 相同的加锁与比对顺序
                        String current = caseService.lockProblemRowThenFingerprint(problemId);
                        assertEquals(fingerprintAtVerifyStart, current);
                        saveLocked.countDown();
                        try {
                            assertTrue(changeAttempted.await(10, TimeUnit.SECONDS), "B 应在 A 持锁期间尝试写入");
                            // 给 B 时间真正进入数据库等待，再提交保存
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        problems.updateStd(problemId, "int main(){}", "思路", 1);
                        org.springframework.transaction.support.TransactionSynchronizationManager
                                .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                                    @Override
                                    public void afterCommit() {
                                        saveCommittedAt.set(Instant.now());
                                    }
                                });
                    });
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });

            assertTrue(saveLocked.await(10, TimeUnit.SECONDS));

            Future<?> changeFuture = pool.submit(() -> {
                SecurityContextHolder.setContext(securityContext);
                try {
                    tx.executeWithoutResult(status -> {
                        changeAttempted.countDown();
                        caseService.saveCases(problemId, List.of(new FpsTestCase("new-in", "new-out")));
                        changeCommittedAt.set(Instant.now());
                    });
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });

            saveFuture.get(30, TimeUnit.SECONDS);
            changeFuture.get(30, TimeUnit.SECONDS);

            assertNotNull(saveCommittedAt.get());
            assertNotNull(changeCommittedAt.get());
            assertTrue(changeCommittedAt.get().isAfter(saveCommittedAt.get()),
                    "用例变更必须被题目行锁挡到保存提交之后（修复点：两段写不再交叉）");

            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM problem_case WHERE problem_id=?",
                    Integer.class, problemId), "线程 B 的用例集合应最终生效");
            assertEquals(0, jdbc.queryForObject("SELECT std_tested FROM problem WHERE id=?", Integer.class, problemId),
                    "保存先提交、变更后提交时，「已测试」必须被清零，不能标成对新用例已测试");
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // 二、用例文件与数据库的回滚一致性
    // ------------------------------------------------------------------

    @Test
    void jsonSaveFailureMidwayKeepsOldCasesAndFilesUsable() {
        Long problemId = fixtureWithTwoCases();
        List<Long> oldCaseIds = jdbc.queryForList("SELECT id FROM problem_case WHERE problem_id=? ORDER BY id",
                Long.class, problemId);
        assertEquals(2, oldCaseIds.size());
        String oldInput0 = jdbc.queryForObject("SELECT input_file_path FROM problem_case WHERE id=?",
                String.class, oldCaseIds.get(0));
        String oldInput1 = jdbc.queryForObject("SELECT input_file_path FROM problem_case WHERE id=?",
                String.class, oldCaseIds.get(1));
        String oldContent0 = storage.readByRelativePath(oldInput0);
        String oldContent1 = storage.readByRelativePath(oldInput1);

        // 第 2 组新用例写出输出文件时失败（真实文件系统 + 真实事务的受控失败）
        AtomicInteger writeCount = new AtomicInteger();
        doAnswer(invocation -> {
            if (writeCount.incrementAndGet() == 2) {
                throw new RuntimeException("受控注入：写出第 2 组输出失败");
            }
            return invocation.callRealMethod();
        }).when(storage).writeOutput(anyLong(), anyLong(), anyString());

        assertThrows(RuntimeException.class, () -> caseService.saveCases(problemId, List.of(
                new FpsTestCase("new-in-1", "new-out-1"),
                new FpsTestCase("new-in-2", "new-out-2"),
                new FpsTestCase("new-in-3", "new-out-3"))));

        // 数据库回滚：旧记录原样存在，计数不变
        List<Long> afterIds = jdbc.queryForList("SELECT id FROM problem_case WHERE problem_id=? ORDER BY id",
                Long.class, problemId);
        assertEquals(oldCaseIds, afterIds, "回滚后必须保留原有用例记录");
        assertEquals(2, jdbc.queryForObject("SELECT test_case_count FROM problem WHERE id=?", Integer.class, problemId));
        // 旧文件仍可读取：判题不会因为一次失败的覆盖保存而读不到数据
        assertEquals(oldContent0, storage.readByRelativePath(oldInput0));
        assertEquals(oldContent1, storage.readByRelativePath(oldInput1));
    }

    @Test
    void zipImportFailureMidwayKeepsOldCasesAndFilesUsable() throws Exception {
        Long problemId = fixtureWithTwoCases();
        List<Long> oldCaseIds = jdbc.queryForList("SELECT id FROM problem_case WHERE problem_id=? ORDER BY id",
                Long.class, problemId);
        String oldInput1 = jdbc.queryForObject("SELECT input_file_path FROM problem_case WHERE id=?",
                String.class, oldCaseIds.get(1));
        String oldContent1 = storage.readByRelativePath(oldInput1);

        byte[] zip = zipOfThreeCases();
        // ZIP 导入写出第 3 组输入文件时失败
        AtomicInteger writeCount = new AtomicInteger();
        doAnswer(invocation -> {
            if (writeCount.incrementAndGet() == 3) {
                throw new RuntimeException("受控注入：写出第 3 组输入失败");
            }
            return invocation.callRealMethod();
        }).when(storage).writeInput(anyLong(), anyLong(), anyString());

        assertThrows(RuntimeException.class, () -> caseService.importCases(problemId, upload(zip, "cases.zip")));

        List<Long> afterIds = jdbc.queryForList("SELECT id FROM problem_case WHERE problem_id=? ORDER BY id",
                Long.class, problemId);
        assertEquals(oldCaseIds, afterIds, "ZIP 导入回滚后必须保留原有用例记录");
        assertEquals(2, jdbc.queryForObject("SELECT test_case_count FROM problem WHERE id=?", Integer.class, problemId));
        assertEquals(oldContent1, storage.readByRelativePath(oldInput1));
    }

    @Test
    void committedSaveRemovesOldFilesAndKeepsNewOnes() {
        Long problemId = fixtureWithTwoCases();
        List<Long> oldCaseIds = jdbc.queryForList("SELECT id FROM problem_case WHERE problem_id=? ORDER BY id",
                Long.class, problemId);

        caseService.saveCases(problemId, List.of(new FpsTestCase("fresh-in", "fresh-out")));

        List<Long> newIds = jdbc.queryForList("SELECT id FROM problem_case WHERE problem_id=? ORDER BY id",
                Long.class, problemId);
        assertEquals(1, newIds.size());
        assertNotEquals(oldCaseIds, newIds, "覆盖保存应重建用例行");
        for (Long oldId : oldCaseIds) {
            assertTrue(!java.nio.file.Files.exists(storage.caseDir(problemId, oldId)),
                    "提交成功后旧用例目录应被删除");
        }
        String inputPath = jdbc.queryForObject("SELECT input_file_path FROM problem_case WHERE id=?",
                String.class, newIds.get(0));
        assertEquals("fresh-in", storage.readByRelativePath(inputPath));
    }

    @Test
    void deleteCaseFailureMidwayKeepsRowAndFile() {
        Long problemId = fixtureWithTwoCases();
        List<Long> oldCaseIds = jdbc.queryForList("SELECT id FROM problem_case WHERE problem_id=? ORDER BY id",
                Long.class, problemId);
        String inputPath = jdbc.queryForObject("SELECT input_file_path FROM problem_case WHERE id=?",
                String.class, oldCaseIds.get(0));
        String content = storage.readByRelativePath(inputPath);

        // resequenceCases 会逐条 updateById 剩余用例：让第一次更新失败来触发真实回滚
        AtomicInteger updates = new AtomicInteger();
        doAnswer(invocation -> {
            if (updates.incrementAndGet() == 1) {
                throw new RuntimeException("受控注入：重排剩余用例时更新失败");
            }
            return invocation.callRealMethod();
        }).when(problemCaseService).updateById(org.mockito.ArgumentMatchers.any());

        assertThrows(RuntimeException.class, () -> caseService.deleteCase(oldCaseIds.get(0)));

        assertEquals(oldCaseIds, jdbc.queryForList("SELECT id FROM problem_case WHERE problem_id=? ORDER BY id",
                        Long.class, problemId),
                "删除回滚后用例记录必须原样保留");
        assertEquals(content, storage.readByRelativePath(inputPath), "文件删除发生在提交之后，回滚不能丢文件");
    }

    // ------------------------------------------------------------------
    // fixture helpers
    // ------------------------------------------------------------------

    /** 建一个教师账号 + 一道私有题，写入 2 组真实用例文件，返回题目 ID。 */
    private Long fixtureWithTwoCases() {
        String username = "pcit_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO user(username,password,nickname,role) VALUES (?, 'synthetic-only', ?, 2)",
                username, username);
        Long userId = jdbc.queryForObject("SELECT id FROM user WHERE username=?", Long.class, username);
        userIds.add(userId);

        loginAsOwner(userId);
        Problem problem = new Problem();
        problem.setTitle("pcit-" + UUID.randomUUID().toString().substring(0, 8));
        problem.setAuthorId(userId);
        problem.setIsPublic(false);
        problem.setIsDeleted(0);
        problem.setTimeLimit(1000);
        problem.setMemoryLimit(256);
        problems.save(problem);
        problemIds.add(problem.getId());

        caseService.saveCases(problem.getId(), List.of(
                new FpsTestCase("old-in-1", "old-out-1"),
                new FpsTestCase("old-in-2", "old-out-2")));
        return problem.getId();
    }

    private void loginAsOwner(Long userId) {
        User user = new User();
        user.setId(userId);
        user.setUsername("u" + userId);
        user.setNickname("u" + userId);
        user.setRole((byte) 2);
        LoginUser loginUser = new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_TEACHER")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    private byte[] zipOfThreeCases() throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 1; i <= 3; i++) {
                zip.putNextEntry(new ZipEntry(i + ".in"));
                zip.write(("zip-in-" + i).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry(i + ".out"));
                zip.write(("zip-out-" + i).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private org.springframework.web.multipart.MultipartFile upload(byte[] content, String name) {
        return new org.springframework.mock.web.MockMultipartFile("file", name, "application/zip", content);
    }
}
