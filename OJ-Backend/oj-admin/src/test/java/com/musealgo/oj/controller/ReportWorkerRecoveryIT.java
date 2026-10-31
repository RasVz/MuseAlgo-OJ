package com.musealgo.oj.controller;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import com.musealgo.oj.OjApplication;
import com.musealgo.oj.analytics.application.ReportJobService;
import com.musealgo.oj.analytics.dto.ReportJobVO;
import com.musealgo.oj.analytics.mapper.ReportJobMapper;
import com.musealgo.oj.system.entity.LoginUser;
import com.musealgo.oj.system.entity.User;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-1 recovery loop (D-2 option 3). An unhealthy report worker used to disable claims forever while the
 * JVM stayed alive, so the default deployment had no way to recover without a human. These tests drive a
 * real child JVM whose renderer blocks uninterruptibly, which is exactly the condition that trips
 * {@code markUnhealthy}.
 *
 * <p>Two behaviours must both hold: on a worker-only instance ({@code exit-on-unhealthy=true}) the process
 * must disappear on its own with the dedicated exit code so a {@code restart} policy can bring it back, and
 * the job it was stuck on must survive through lease expiry. With the flag off (the default, i.e. a process
 * that also serves web traffic) nothing may self-terminate — recovery stays the supervisor's decision.
 */
@SpringBootTest(classes = OjApplication.class, properties = {
        "oj.messaging.rabbitmq-enabled=false",
        "oj.judge.worker.enabled=false",
        "report.worker.enabled=false",
        "report.notification.worker-enabled=false",
        "report.weekly-enabled=false",
        "report.retention-cleanup-enabled=false"
})
class ReportWorkerRecoveryIT {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 10, 12);
    private static final LocalDate PERIOD_END = PERIOD_START.plusDays(6);
    private static final int UNHEALTHY_EXIT_CODE = 70;

    @Autowired private ReportJobService reportJobService;
    @Autowired private ReportJobMapper jobMapper;
    @Autowired private JdbcTemplate jdbc;

    private Long teacherId;
    private Long studentId;
    private Long classId;
    private final Set<Long> jobIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<Long> syntheticUserIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<Long> syntheticClassIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @AfterEach
    void cleanup() {
        for (Long id : jobIds) {
            Long snapshotId = jdbc.query("SELECT snapshot_id FROM report_job WHERE id=?", rs ->
                    rs.next() ? rs.getObject(1, Long.class) : null, id);
            jdbc.update("DELETE FROM report_notification_intent WHERE job_id=?", id);
            jdbc.update("DELETE FROM user_notification WHERE target_type='REPORT' AND target_id=?", id);
            jdbc.update("DELETE FROM report_delivery WHERE job_id=?", id);
            jdbc.update("DELETE FROM report_job_attempt WHERE job_id=?", id);
            jdbc.update("DELETE FROM report_job WHERE id=?", id);
            if (snapshotId != null) jdbc.update("DELETE FROM student_profile_snapshot WHERE id=?", snapshotId);
        }
        for (Long id : syntheticClassIds) {
            jdbc.update("DELETE FROM class_student WHERE class_id=?", id);
            jdbc.update("DELETE FROM class WHERE id=?", id);
        }
        for (Long id : syntheticUserIds) {
            jdbc.update("DELETE FROM user WHERE id=?", id);
        }
        jobIds.clear();
        syntheticUserIds.clear();
        syntheticClassIds.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void sameJvmCanRunNewGenerationWhileExpiredGenerationStillBlocks() throws Exception {
        fixture("same-jvm-generation");
        ChildJvm child = ChildJvm.start("overlap", false);
        try {
            assertTrue(child.contextReady.await(90, TimeUnit.SECONDS), child.safeStatus());
            ReportJobVO accepted = enqueue();
            assertTrue(child.renderEntered.await(60, TimeUnit.SECONDS), child.safeStatus());
            assertEquals(1, count("SELECT attempts FROM report_job WHERE id=?", accepted.id()));
            Long oldGeneration = jdbc.queryForObject("SELECT generation_no FROM report_job WHERE id=?", Long.class, accepted.id());
            String oldOwner = jdbc.queryForObject("SELECT lease_owner FROM report_job WHERE id=?", String.class, accepted.id());
            jdbc.update("UPDATE report_job SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id=?", accepted.id());

            assertTrue(child.twoRendersEntered.await(20, TimeUnit.SECONDS), child.safeStatus());
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertEquals("GENERATED", status(accepted.id())));
            assertEquals(2, count("SELECT attempts FROM report_job WHERE id=?", accepted.id()));
            assertEquals(2, count("SELECT generation_no FROM report_job WHERE id=?", accepted.id()));
            assertEquals(2, count("SELECT COUNT(*) FROM report_job_attempt WHERE job_id=?", accepted.id()));
            assertEquals(1, count("SELECT terminal_revision FROM report_job WHERE id=?", accepted.id()));
            assertEquals(0, jobMapper.finishGenerated(accepted.id(), oldGeneration, oldOwner,
                    LocalDateTime.now().plusHours(1), null, "<html>stale</html>"));

            child.releaseRenderer();
            assertTrue(child.renderReleased.await(20, TimeUnit.SECONDS), child.safeStatus());
            assertEquals("GENERATED", status(accepted.id()));
            assertEquals(1, count("SELECT COUNT(*) FROM report_delivery WHERE job_id=? AND channel='IN_APP'", accepted.id()));
        } finally {
            child.stop();
        }
    }

    @Test
    void twoEnabledProcessesFenceAnExpiredOwnerAndFinishOnlyOnce() throws Exception {
        fixture("two-workers");
        ChildJvm first = ChildJvm.start("hold", false);
        ChildJvm second = ChildJvm.start("hold", false);
        try {
            assertTrue(first.contextReady.await(90, TimeUnit.SECONDS), first.safeStatus());
            assertTrue(second.contextReady.await(90, TimeUnit.SECONDS), second.safeStatus());
            ReportJobVO accepted = enqueue();
            Awaitility.await().atMost(Duration.ofSeconds(60)).until(() ->
                    first.renderEntered.getCount() + second.renderEntered.getCount() == 1);
            ChildJvm old = first.renderEntered.getCount() == 0 ? first : second;
            ChildJvm current = old == first ? second : first;
            Long oldGeneration = jdbc.queryForObject("SELECT generation_no FROM report_job WHERE id=?", Long.class, accepted.id());
            String oldOwner = jdbc.queryForObject("SELECT lease_owner FROM report_job WHERE id=?", String.class, accepted.id());
            assertEquals(1, count("SELECT COUNT(*) FROM report_job_attempt WHERE job_id=?", accepted.id()));
            assertEquals(1, count("SELECT attempts FROM report_job WHERE id=?", accepted.id()));

            jdbc.update("UPDATE report_job SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id=?", accepted.id());
            assertTrue(current.renderEntered.await(60, TimeUnit.SECONDS), current.safeStatus());
            assertEquals(2, count("SELECT attempts FROM report_job WHERE id=?", accepted.id()));
            assertEquals(2, count("SELECT generation_no FROM report_job WHERE id=?", accepted.id()));
            assertEquals(0, jobMapper.finishGenerated(accepted.id(), oldGeneration, oldOwner,
                    LocalDateTime.now().plusHours(1), null, "<html>stale</html>"));
            old.releaseRenderer();
            assertTrue(old.renderReleased.await(20, TimeUnit.SECONDS), old.safeStatus());
            assertEquals("GENERATING", status(accepted.id()));
            current.releaseRenderer();
            Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                    assertEquals("GENERATED", status(accepted.id())));
            assertEquals(1, count("SELECT terminal_revision FROM report_job WHERE id=?", accepted.id()));
            assertEquals(2, count("SELECT COUNT(*) FROM report_job_attempt WHERE job_id=?", accepted.id()));
            assertEquals(1, count("SELECT COUNT(*) FROM report_delivery WHERE job_id=? AND channel='IN_APP'", accepted.id()));
        } finally {
            first.stop();
            second.stop();
        }
    }

    @Test
    void unhealthyDedicatedWorkerExitsByItselfAndStuckJobSurvivesLeaseExpiry() throws Exception {
        fixture("recovery-exit");
        ReportJobVO accepted = enqueue();

        // Short runtime/grace so the watchdog trips the deadline quickly; exit-grace 0 keeps the test fast.
        ChildJvm stuck = ChildJvm.start("block", true);
        try {
            assertTrue(stuck.renderEntered.await(60, TimeUnit.SECONDS), stuck.safeStatus());
            assertEquals("GENERATING", status(accepted.id()));

            // The whole point of the fix: nobody kills this process, it must go away on its own.
            assertTrue(stuck.process.waitFor(90, TimeUnit.SECONDS),
                    "worker-only instance stayed alive after going unhealthy: " + stuck.safeStatus());
            assertEquals(UNHEALTHY_EXIT_CODE, stuck.process.exitValue(),
                    "unexpected exit code: " + stuck.safeStatus());

            String log = String.join("\n", stuck.output);
            assertTrue(log.contains("OJ_ALERT report_worker_unhealthy"), log);
            assertTrue(log.contains("Report worker readiness is DOWN"), log);
            assertTrue(log.contains("OJ_ALERT report_worker_self_terminate"), log);
        } finally {
            stuck.stop();
        }

        // The stuck attempt must not be lost: its lease expires and a healthy worker reclaims the job.
        assertEquals(1, count("SELECT COUNT(*) FROM report_job_attempt WHERE job_id=?", accepted.id()));
        jdbc.update("UPDATE report_job SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id=?",
                accepted.id());

        ChildJvm healthy = ChildJvm.start("complete", true);
        try {
            Awaitility.await().atMost(Duration.ofSeconds(90)).untilAsserted(() ->
                    assertEquals("GENERATED", status(accepted.id())));
            assertFalse(String.join("\n", healthy.output).contains("report_worker_self_terminate"),
                    "a healthy worker must not self-terminate: " + healthy.safeStatus());
        } finally {
            healthy.stop();
        }

        ReportJobVO generated = reportJobService.getReport(accepted.id());
        assertNotNull(generated.snapshotId());
        assertEquals(1, count("SELECT COUNT(*) FROM report_delivery WHERE job_id=? AND channel='IN_APP' "
                + "AND status='DELIVERED'", accepted.id()));
    }

    @Test
    void unhealthyWorkerWithoutExitFlagStaysAliveForExternalSupervisor() throws Exception {
        fixture("recovery-noexit");
        ReportJobVO accepted = enqueue();

        ChildJvm child = ChildJvm.start("block", false);
        try {
            assertTrue(child.renderEntered.await(60, TimeUnit.SECONDS), child.safeStatus());
            Awaitility.await().atMost(Duration.ofSeconds(60)).until(() ->
                    String.join("\n", child.output).contains("OJ_ALERT report_worker_unhealthy"));

            // Default deployment shape (web + worker in one process): never take user traffic down.
            String log = String.join("\n", child.output);
            assertTrue(log.contains("exitOnUnhealthy=false"), log);
            assertFalse(log.contains("report_worker_self_terminate"), log);

            Thread.sleep(10_000);
            assertTrue(child.process.isAlive(),
                    "worker without exit-on-unhealthy must not self-terminate: " + child.safeStatus());
            assertEquals("GENERATING", status(accepted.id()));
        } finally {
            child.stop();
        }
    }

    private String status(Long jobId) {
        return jdbc.queryForObject("SELECT status FROM report_job WHERE id=?", String.class, jobId);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private ReportJobVO enqueue() {
        login(teacherId);
        ReportJobVO result = reportJobService.requestReport(studentId, classId, PERIOD_START, PERIOD_END);
        jobIds.add(result.id());
        return result;
    }

    private void fixture(String prefix) {
        cleanup();
        teacherId = insertUser("TEACHER", "rcv_" + prefix + "_teacher_");
        studentId = insertUser("STUDENT", "rcv_" + prefix + "_student_");
        classId = insertClass(teacherId);
        jdbc.update("INSERT INTO class_student(class_id,student_id) VALUES (?,?)", classId, studentId);
    }

    private void login(Long userId) {
        User user = new User();
        user.setId(userId);
        user.setUsername("u" + userId);
        user.setNickname("u" + userId);
        user.setRole(Byte.valueOf("2"));
        LoginUser loginUser = new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_TEACHER")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    private Long insertUser(String role, String prefix) {
        String username = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO user(username,password,nickname,role) VALUES (?, 'synthetic-only', ?, ?)",
                username, username, "TEACHER".equals(role) ? 2 : 1);
        Long id = jdbc.queryForObject("SELECT id FROM user WHERE username=?", Long.class, username);
        syntheticUserIds.add(id);
        return id;
    }

    private Long insertClass(Long ownerId) {
        String name = "rcv-class-" + UUID.randomUUID();
        jdbc.update("INSERT INTO class(name,teacher_id,invite_code) VALUES (?,?,?)",
                name, ownerId, UUID.randomUUID().toString().substring(0, 8));
        Long id = jdbc.queryForObject("SELECT id FROM class WHERE name=?", Long.class, name);
        syntheticClassIds.add(id);
        return id;
    }

    /**
     * Launches the real Spring worker in a child JVM, reusing {@link ReportJobIT.WorkerProbe} so the
     * blocking renderer stub is defined in exactly one place. Only the recovery flags differ per case.
     */
    private static final class ChildJvm {
        private final Process process;
        private final CountDownLatch renderEntered = new CountDownLatch(1);
        private final CountDownLatch twoRendersEntered = new CountDownLatch(2);
        private final CountDownLatch renderReleased = new CountDownLatch(1);
        private final CountDownLatch contextReady = new CountDownLatch(1);
        private final List<String> output = new CopyOnWriteArrayList<>();

        private ChildJvm(Process process) {
            this.process = process;
            Thread drain = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.add(line);
                        if (line.contains("RPT_PROBE_RENDER_ENTERED")) {
                            renderEntered.countDown();
                            twoRendersEntered.countDown();
                        }
                        if (line.contains("RPT_PROBE_RENDER_RELEASED")) renderReleased.countDown();
                        if (line.contains("RPT_PROBE_CONTEXT_READY")) contextReady.countDown();
                    }
                } catch (Exception ignored) {
                    // Stream closure is expected once the child exits on its own or is stopped.
                }
            }, "rcv-child-stdout-reader");
            drain.setDaemon(true);
            drain.start();
        }

        private static ChildJvm start(String mode, boolean exitOnUnhealthy) throws Exception {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            List<String> command = new ArrayList<>(List.of(java, "-Xmx256m", "-Duser.timezone=UTC",
                    "-Dreport.worker.probe.mode=" + mode, "-cp", classpath,
                    ReportJobIT.WorkerProbe.class.getName(),
                    "--spring.main.web-application-type=none",
                    "--spring.autoconfigure.exclude=com.github.xiaoymin.knife4j.spring.configuration.Knife4jAutoConfiguration",
                    "--spring.flyway.enabled=false",
                    "--oj.messaging.rabbitmq-enabled=false",
                    "--oj.judge.worker.enabled=false",
                    "--report.worker.enabled=true",
                    "--report.worker.threads=" + ("overlap".equals(mode) ? 2 : 1),
                    "--report.worker.poll-interval-ms=100",
                    "--report.worker.watchdog-interval-ms=100",
                    "--report.worker.heartbeat-interval-ms=1000",
                    // Only the blocking stub should trip the runtime deadline within a few seconds; a
                    // completing worker gets a normal budget so a slow first claim cannot look unhealthy.
                    "--report.worker.max-runtime-seconds=" + ("block".equals(mode) ? 2 : 60),
                    "--report.worker.cancel-grace-seconds=1",
                    "--report.worker.recover-legacy-generating=false",
                    "--report.notification.worker-enabled=false",
                    "--report.weekly-enabled=false",
                    "--report.retention-cleanup-enabled=false"));
            command.add("--report.worker.exit-on-unhealthy=" + exitOnUnhealthy);
            command.add("--report.worker.exit-grace-seconds=0");
            command.add("--report.worker.exit-hard-timeout-seconds=10");
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            return new ChildJvm(process);
        }

        private String safeStatus() {
            int from = Math.max(0, output.size() - 25);
            return "child JVM exited=" + !process.isAlive() + ", outputLines=" + output.size()
                    + ", tail=" + String.join(" | ", output.subList(from, output.size()));
        }

        private void releaseRenderer() throws Exception {
            process.getOutputStream().write('\n');
            process.getOutputStream().flush();
        }

        private void stop() throws InterruptedException {
            if (!process.isAlive()) return;
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(20, TimeUnit.SECONDS), "child JVM remained alive after forced stop");
            }
        }
    }
}
