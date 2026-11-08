package com.musealgo.oj.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.musealgo.oj.analytics.application.ReportRetentionTask;
import com.musealgo.oj.api.report.ReportIntentCommand;
import com.musealgo.oj.api.report.ReportNotificationPort;
import com.musealgo.oj.api.report.ReportNotificationStatusQuery;
import com.musealgo.oj.community.notification.ReportNotificationAdminService;
import com.musealgo.oj.community.notification.ReportNotificationClaim;
import com.musealgo.oj.community.notification.ReportNotificationDispatchRepository;
import com.musealgo.oj.community.notification.ReportNotificationDispatcher;
import com.musealgo.oj.community.service.NotificationService;
import com.musealgo.oj.system.entity.LoginUser;
import com.musealgo.oj.system.entity.User;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Candidate real-MySQL acceptance for notice persistence, dispatch, retry, and admin redrive. */
@SpringBootTest(properties = {
        "oj.messaging.rabbitmq-enabled=false",
        "oj.judge.worker.enabled=false",
        "report.worker.enabled=false",
        "report.notification.worker-enabled=false",
        "report.weekly-enabled=false",
        "report.retention-cleanup-enabled=false"
})
@AutoConfigureMockMvc
class ReportNotificationIT {

    private static final String MALFORMED_PREFERENCES =
            "{\"notifications\":{\"reports\":\"invalid\"}}";
    private static final String ENABLED_PREFERENCES =
            "{\"notifications\":{\"interactions\":true,\"reports\":true}}";
    private static final String DISABLED_PREFERENCES = "{\"notifications\":{\"reports\":false}}";
    private static final String REDRIVE_REASON = "通知投递失败，重投一次并核对站内提醒链路";

    @Autowired private ReportNotificationPort intents;
    @Autowired private ReportNotificationStatusQuery statusQuery;
    @Autowired private NotificationService notificationService;
    @Autowired private ReportNotificationDispatcher dispatcher;
    @Autowired private ReportNotificationDispatchRepository dispatchRepository;
    @Autowired private ReportNotificationAdminService adminService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MockMvc mockMvc;
    @Autowired private ReportRetentionTask retention;

    private TransactionTemplate transactions;
    private Long userId;
    private Long jobId;
    private Long classId;
    private Long studentId;
    private final List<Long> createdUsers = new ArrayList<>();
    private final List<Long> createdClasses = new ArrayList<>();

    @BeforeEach
    void createFixture() {
        transactions = new TransactionTemplate(transactionManager);
        String username = "rni_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO user(username, password, nickname, role, preferences) VALUES (?, 'test-only', ?, 2, ?)",
                username, username, "{\"notifications\":{\"interactions\":true,\"reports\":true}}");
        userId = jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
        createdUsers.add(userId);

        long syntheticStudentId = userId;
        long syntheticClassId = userId + 1;
        LocalDate start = LocalDate.of(2026, 1, 5);
        LocalDate end = LocalDate.of(2026, 1, 11);
        jdbc.update("""
                        INSERT INTO report_job (student_id, class_id, period_start, period_end, status, requester_id)
                        VALUES (?, ?, ?, ?, 'FAILED', ?)
                        """, syntheticStudentId, syntheticClassId, start, end, userId);
        jobId = jdbc.queryForObject("""
                SELECT id FROM report_job WHERE student_id = ? AND class_id = ? AND period_start = ? AND period_end = ?
                """, Long.class, syntheticStudentId, syntheticClassId, start, end);
    }

    @AfterEach
    void cleanup() {
        if (jobId != null) {
            jdbc.update("DELETE FROM report_notification_intent WHERE job_id = ?", jobId);
            jdbc.update("DELETE FROM user_notification WHERE target_type = 'REPORT' AND target_id = ?", jobId);
            jdbc.update("DELETE FROM report_job WHERE id = ?", jobId);
        }
        for (Long classId : createdClasses) {
            jdbc.update("DELETE FROM class_student WHERE class_id = ?", classId);
            jdbc.update("DELETE FROM class WHERE id = ?", classId);
        }
        for (Long id : createdUsers) {
            jdbc.update("DELETE FROM audit_log WHERE user_id = ?", id);
            jdbc.update("DELETE FROM user_notification WHERE user_id = ?", id);
            jdbc.update("DELETE FROM user WHERE id = ?", id);
        }
    }

    @Test
    void stagingJoinsCallerTransactionAndDuplicateSourceCodeIsImmutable() {
        ReportIntentCommand failed = failedCommand(userId, 1L, "EMPTY_RENDER");
        assertThrows(IllegalTransactionStateException.class, () -> intents.stageFailedIntent(failed));

        transactions.executeWithoutResult(status -> {
            intents.stageFailedIntent(failed);
            intents.stageFailedIntent(failed);
        });
        assertEquals(1, count("SELECT COUNT(*) FROM report_notification_intent WHERE job_id = ?", jobId));
        var state = jdbc.queryForMap("""
                SELECT status, attempts, max_attempts, source_error_code, last_error_code
                FROM report_notification_intent WHERE job_id = ?
                """, jobId);
        assertEquals("PENDING", state.get("status"));
        assertEquals(0, ((Number) state.get("attempts")).intValue());
        assertEquals(5, ((Number) state.get("max_attempts")).intValue());
        assertEquals("EMPTY_RENDER", state.get("source_error_code"));
        assertNull(state.get("last_error_code"));

        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status ->
                intents.stageFailedIntent(failedCommand(userId, 1L, "GENERATOR_UNAVAILABLE"))));
        assertEquals(1, count("SELECT COUNT(*) FROM report_notification_intent WHERE job_id = ?", jobId));
        assertEquals("EMPTY_RENDER", jdbc.queryForObject(
                "SELECT source_error_code FROM report_notification_intent WHERE job_id = ?", String.class, jobId));
    }

    @Test
    void unassignedFailureIsPersistedAsTerminalPermissionSkipWithoutRecipient() {
        transactions.executeWithoutResult(status -> intents.stageFailedIntent(failedCommand(null, 1L, "LEGACY_ACTOR_MISSING")));
        var state = jdbc.queryForMap("""
                SELECT event_key, recipient_id, status, attempts, next_attempt_at, source_error_code
                FROM report_notification_intent WHERE job_id = ?
                """, jobId);
        assertEquals("REPORT_FAILED:" + jobId + ":1:1:UNASSIGNED", state.get("event_key"));
        assertNull(state.get("recipient_id"));
        assertEquals("SKIPPED_PERMISSION", state.get("status"));
        assertEquals(0, ((Number) state.get("attempts")).intValue());
        assertNull(state.get("next_attempt_at"));
        assertEquals("LEGACY_ACTOR_MISSING", state.get("source_error_code"));
        assertEquals(0, count("SELECT COUNT(*) FROM user_notification WHERE target_id = ? AND target_type = 'REPORT'", jobId));
        assertTrue(statusQuery.findForJob(jobId, userId, 1L).intents().isEmpty());
    }

    @Test
    void legacyGenerationZeroFailureCanBeStagedWithoutChangingItsGeneration() {
        ReportIntentCommand legacy = new ReportIntentCommand(
                jobId, userId + 1, userId, userId, 0L, 1L, "LEGACY_ATTEMPTS_EXHAUSTED");
        transactions.executeWithoutResult(status -> intents.stageFailedIntent(legacy));

        var state = jdbc.queryForMap("""
                SELECT event_key, generation_no, terminal_revision, source_error_code, status
                FROM report_notification_intent WHERE job_id = ?
                """, jobId);
        assertEquals("REPORT_FAILED:" + jobId + ":0:1:" + userId, state.get("event_key"));
        assertEquals(0, ((Number) state.get("generation_no")).intValue());
        assertEquals(1, ((Number) state.get("terminal_revision")).intValue());
        assertEquals("LEGACY_ATTEMPTS_EXHAUSTED", state.get("source_error_code"));
        assertEquals("PENDING", state.get("status"));
    }

    @Test
    void statusQueryReturnsOnlyRecipientReadFactsAndStableInstantAcrossJvmZones() {
        transactions.executeWithoutResult(status -> intents.stageReadyIntent(readyCommand(userId, 1L)));
        String wallClock = jdbc.queryForObject("""
                SELECT DATE_FORMAT(next_attempt_at, '%Y-%m-%d %H:%i:%s.%f')
                FROM report_notification_intent WHERE job_id = ? AND event_kind = 'REPORT_READY'
                """, String.class, jobId);
        LocalDateTime databaseTime = LocalDateTime.parse(
                wallClock, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"));
        Instant expectedInstant = databaseTime.atZone(ZoneId.of("Asia/Shanghai")).toInstant();

        var snapshot = statusQuery.findForJob(jobId, userId, 1L);
        assertEquals(1, snapshot.intents().size());
        assertEquals(expectedInstant, snapshot.intents().get(0).nextAttemptAt());
        assertNull(snapshot.intents().get(0).notificationId());
        assertFalse(snapshot.intents().get(0).isRead());
        assertNull(snapshot.intents().get(0).readAt());

        Long noticeId = transactions.execute(status -> {
            Long created = insertNotification(userId, "REPORT_READY", jobId);
            jdbc.update("""
                    UPDATE report_notification_intent
                    SET status = 'DELIVERED', notification_id = ?, delivered_at = CURRENT_TIMESTAMP(3),
                        next_attempt_at = NULL
                    WHERE job_id = ? AND event_kind = 'REPORT_READY'
                    """, created, jobId);
            return created;
        });
        jdbc.update("UPDATE user_notification SET is_read = 1, read_time = '2026-10-21 08:09:10' WHERE id = ?", noticeId);
        var readSnapshot = statusQuery.findForJob(jobId, userId, 1L).intents().get(0);
        assertEquals(noticeId, readSnapshot.notificationId());
        assertTrue(readSnapshot.isRead());
        assertEquals(Instant.parse("2026-10-21T00:09:10Z"), readSnapshot.readAt());
        assertTrue(statusQuery.findForJob(jobId, userId, 2L).intents().isEmpty());

        Long otherRecipient = insertUser("{\"notifications\":{\"reports\":true}}");
        assertTrue(statusQuery.findForJob(jobId, otherRecipient, 1L).intents().isEmpty());
    }

    @Test
    void cleanupTerminatesExpiredActiveIntentsAndDeletesExactLegacyRows() {
        transactions.executeWithoutResult(status -> {
            intents.stageReadyIntent(readyCommand(userId, 1L));
            intents.stageFailedIntent(failedCommand(userId, 1L, "EMPTY_RENDER"));
            Long linkedNotice = insertNotification(userId, "REPORT_READY", jobId);
            jdbc.update("""
                    UPDATE report_notification_intent
                    SET status = 'DELIVERED', notification_id = ?, delivered_at = CURRENT_TIMESTAMP(3),
                        next_attempt_at = NULL
                    WHERE job_id = ? AND event_kind = 'REPORT_READY'
                    """, linkedNotice, jobId);
            insertNotification(userId, "REPORT_FAILED", jobId);
            insertNotification(userId, "COMMENT", jobId);
        });

        Set<Long> deleted = transactions.execute(status -> intents.deleteTerminalIntentsForExpiredJobs(List.of(jobId, jobId)));
        assertEquals(Set.of(jobId), deleted);
        assertEquals(0, count("SELECT COUNT(*) FROM report_notification_intent WHERE job_id = ?", jobId));
        assertEquals(0, count("SELECT COUNT(*) FROM user_notification WHERE target_type = 'REPORT' AND target_id = ? AND type IN ('REPORT_READY', 'REPORT_FAILED')", jobId));
        assertEquals(1, count("SELECT COUNT(*) FROM user_notification WHERE target_type = 'REPORT' AND target_id = ? AND type = 'COMMENT'", jobId));
    }

    @Test
    void preferenceCloseCommittingDuringDeliveryPreventsNotification() throws Exception {
        makeDispatchableJob("GENERATED");
        Long intentId = stageReady(1L);
        ReportNotificationClaim claim = dispatchRepository.claimIntent(intentId);
        assertNotNull(claim);
        assertTrue(notificationService.loadReportPreferencesStrict(userId).reports());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<Future<ReportNotificationDispatchRepository.DeliveryOutcome>> delivery =
                new AtomicReference<>();
        try {
            transactions.executeWithoutResult(tx -> {
                // Hold an uncommitted close. A plain read sees the old enabled value; a locking
                // read must wait for this commit before deciding whether to insert the notice.
                setPreferences(DISABLED_PREFERENCES);
                Long blocker = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                delivery.set(pool.submit(() -> dispatchRepository.deliver(claim)));
                awaitDatabaseWaitOrCompletion(delivery.get(), blocker);
            });
            assertEquals(ReportNotificationDispatchRepository.DeliveryOutcome.SKIPPED,
                    delivery.get().get(15, TimeUnit.SECONDS));
            assertEquals("SKIPPED_PREFERENCE", intentState(intentId).get("status"));
            assertEquals(0, count("SELECT COUNT(*) FROM user_notification WHERE target_type='REPORT' AND target_id=?", jobId));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void reenabledPreferenceBeforeSkipRequeuesAndEventuallyDelivers() {
        makeDispatchableJob("GENERATED");
        Long intentId = stageReady(1L);
        ReportNotificationClaim claim = dispatchRepository.claimIntent(intentId);
        assertNotNull(claim);
        setPreferences(DISABLED_PREFERENCES);
        assertFalse(notificationService.loadReportPreferencesStrict(userId).reports());
        setPreferences(ENABLED_PREFERENCES);

        assertTrue(dispatchRepository.skipForPreference(claim));
        Map<String, Object> state = intentState(intentId);
        assertEquals("PENDING", state.get("status"));
        assertNull(state.get("dispatch_owner"));
        assertNull(state.get("dispatch_lease_until"));
        assertEquals(1, ((Number) state.get("attempts")).intValue(), "claim budget must not reset");
        assertTrue(dispatcher.dispatchIntent(intentId));
        assertEquals("DELIVERED", intentState(intentId).get("status"));
    }

    @Test
    void retentionDeletesFutureRetryWhenNotificationWorkerIsDisabled() {
        makeDispatchableJob("GENERATED");
        Long intentId = stageReady(1L);
        jdbc.update("UPDATE report_job SET create_time=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 13 MONTH) WHERE id=?", jobId);
        jdbc.update("UPDATE report_notification_intent SET next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 YEAR) WHERE id=?", intentId);
        runRetention();
        assertEquals(0, count("SELECT COUNT(*) FROM report_job WHERE id=?", jobId));
        assertEquals(0, count("SELECT COUNT(*) FROM report_notification_intent WHERE id=?", intentId));
    }

    @Test
    void retentionDeletesExpiredReportWithLiveDispatchLeaseAndFencesOldClaim() {
        makeDispatchableJob("GENERATED");
        Long intentId = stageReady(1L);
        ReportNotificationClaim claim = dispatchRepository.claimIntent(intentId);
        assertNotNull(claim);
        jdbc.update("UPDATE report_job SET create_time=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 13 MONTH) WHERE id=?", jobId);
        runRetention();
        assertEquals(0, count("SELECT COUNT(*) FROM report_job WHERE id=?", jobId));
        assertEquals(ReportNotificationDispatchRepository.DeliveryOutcome.FENCE_LOST,
                dispatchRepository.deliver(claim));
        assertEquals(0, count("SELECT COUNT(*) FROM user_notification WHERE target_type='REPORT' AND target_id=?", jobId));
    }

    @Test
    void expiredNoticeCleanupRollsBackTogetherWithCaller() {
        makeDispatchableJob("GENERATED");
        Long intentId = stageReady(1L);
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(tx -> {
            assertEquals(Set.of(jobId), intents.deleteTerminalIntentsForExpiredJobs(List.of(jobId)));
            throw new IllegalStateException("synthetic report deletion failure");
        }));
        assertEquals("PENDING", intentState(intentId).get("status"));
        assertEquals(1, count("SELECT COUNT(*) FROM report_job WHERE id=?", jobId));
    }

    @Test
    void concurrentAdminRedriveAuditsSuccessAndConflictAndKeepsOriginalError() throws Exception {
        makeDispatchableJob("GENERATED");
        Long intentId = stageReady(1L);
        setPreferences(MALFORMED_PREFERENCES);
        exhaustDispatch(intentId);
        setPreferences(ENABLED_PREFERENCES);
        Long leftAdmin = insertUser(ENABLED_PREFERENCES);
        Long rightAdmin = insertUser(ENABLED_PREFERENCES);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> first = pool.submit(() -> redriveHttpAfter(start, intentId, leftAdmin));
            Future<Integer> second = pool.submit(() -> redriveHttpAfter(start, intentId, rightAdmin));
            start.countDown();
            assertEquals(Set.of(200, 409), Set.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
        awaitAuditRows(leftAdmin, rightAdmin);
        List<Map<String, Object>> audit = jdbc.queryForList("""
                SELECT status, request_params, error_message FROM audit_log
                WHERE module='report_notification' AND action='redrive' AND user_id IN (?, ?)
                """, leftAdmin, rightAdmin);
        assertEquals(2, audit.size());
        assertEquals(1, audit.stream().filter(row -> ((Number) row.get("status")).intValue() == 1).count());
        Map<String, Object> success = audit.stream().filter(row -> ((Number) row.get("status")).intValue() == 1).findFirst().orElseThrow();
        assertTrue(String.valueOf(success.get("request_params")).contains("PREFERENCE_READ_FAILED"));
        Map<String, Object> rejected = audit.stream().filter(row -> ((Number) row.get("status")).intValue() == 0).findFirst().orElseThrow();
        assertTrue(String.valueOf(rejected.get("error_message")).contains("409"));
        assertTrue(dispatcher.dispatchIntent(intentId));
        assertNull(intentState(intentId).get("last_error_code"));
        assertTrue(String.valueOf(success.get("request_params")).contains("PREFERENCE_READ_FAILED"),
                "delivery must not erase the prior error recorded in the audit");
    }

    @Test
    void notificationStatusAllowsRecipientAndRejectsUnrelatedTeacherAndStudents() throws Exception {
        makeDispatchableJob("GENERATED");
        stageReady(1L);
        String path = "/profile/report/jobs/" + jobId + "/notification-status";
        mockMvc.perform(get(path).with(authentication(authenticationFor(userId, "ROLE_TEACHER", (byte) 2))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        Long other = insertUser(ENABLED_PREFERENCES);
        mockMvc.perform(get(path).with(authentication(authenticationFor(other, "ROLE_TEACHER", (byte) 2))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(get(path).with(authentication(authenticationFor(studentId, "ROLE_STUDENT", (byte) 1))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(path).with(authentication(authenticationFor(other, "ROLE_STUDENT", (byte) 1))))
                .andExpect(status().isForbidden());
    }

    private void runRetention() {
        ReflectionTestUtils.setField(retention, "enabled", true);
        try {
            retention.cleanupExpiredReports();
        } finally {
            ReflectionTestUtils.setField(retention, "enabled", false);
        }
    }

    private void awaitDatabaseWaitOrCompletion(Future<?> task, Long blocker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (task.isDone()) return;
            if (count("""
                    SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.threads t ON t.THREAD_ID=w.BLOCKING_THREAD_ID
                    WHERE t.PROCESSLIST_ID=?
                    """, blocker) > 0) return;
            Thread.yield();
        }
        throw new AssertionError("delivery neither completed nor waited for the preference writer");
    }

    private int redriveHttpAfter(CountDownLatch start, Long intentId, Long adminId) throws Exception {
        assertTrue(start.await(10, TimeUnit.SECONDS));
        MvcResult result = mockMvc.perform(post("/admin/report-notification-intents/" + intentId + "/redrive")
                        .contentType(MediaType.APPLICATION_JSON).content(redriveBody(REDRIVE_REASON))
                        .with(authentication(authenticationFor(adminId, "ROLE_ADMIN", (byte) 3))))
                .andExpect(status().isOk()).andReturn();
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("code").asInt();
    }

    private void awaitAuditRows(Long left, Long right) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (count("SELECT COUNT(*) FROM audit_log WHERE module='report_notification' AND action='redrive' AND user_id IN (?, ?)", left, right) == 2) return;
            Thread.yield();
        }
        throw new AssertionError("both administrator audit records must be persisted");
    }

    @Test
    void reportPreferenceFiltersReadyAndFailedConsistentlyAndStrictReadFailsClosed() {
        insertNotification(userId, "REPORT_READY", jobId);
        insertNotification(userId, "REPORT_FAILED", jobId);
        insertNotification(userId, "LIKE", jobId);
        jdbc.update("UPDATE user SET preferences = ? WHERE id = ?",
                "{\"notifications\":{\"interactions\":false,\"reports\":false}}", userId);
        assertTrue(notificationService.list(userId, null).isEmpty());
        assertEquals(0, notificationService.unreadCount(userId));
        assertFalse(notificationService.loadReportPreferencesStrict(userId).reports());

        jdbc.update("UPDATE user SET preferences = ? WHERE id = ?",
                "{\"notifications\":{\"interactions\":false,\"reports\":true}}", userId);
        List<Map<String, Object>> visible = notificationService.list(userId, null);
        assertEquals(2, visible.size());
        assertTrue(visible.stream().allMatch(row -> Set.of("REPORT_READY", "REPORT_FAILED").contains(row.get("type"))));
        assertEquals(2, notificationService.unreadCount(userId));

        jdbc.update("UPDATE user SET preferences = ? WHERE id = ?",
                "{\"notifications\":{\"reports\":\"invalid\"}}", userId);
        assertThrows(IllegalStateException.class, () -> notificationService.loadReportPreferencesStrict(userId));
        assertTrue(notificationService.loadPreferences(userId).reports(),
                "existing non-report preferences retain their fail-open behavior");
    }

    @Test
    void concurrentDispatchClaimsOnceAndWritesOneSafeInAppNotification() throws Exception {
        makeDispatchableJob("GENERATED");
        Long intentId = stageReady(1L);
        restageReady(1L);
        assertEquals(1, count("SELECT COUNT(*) FROM report_notification_intent WHERE job_id = ? AND event_kind = 'REPORT_READY'", jobId));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        boolean left;
        boolean right;
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> work = () -> {
                start.await(10, TimeUnit.SECONDS);
                return dispatcher.dispatchIntent(intentId);
            };
            Future<Boolean> first = pool.submit(work);
            Future<Boolean> second = pool.submit(work);
            start.countDown();
            left = first.get(30, TimeUnit.SECONDS);
            right = second.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertTrue(left ^ right, "exactly one concurrent worker may commit a delivery");

        assertFalse(dispatcher.dispatchIntent(intentId));
        Map<String, Object> state = intentState(intentId);
        assertEquals("DELIVERED", state.get("status"));
        assertEquals(1, ((Number) state.get("attempts")).intValue());
        assertNotNull(state.get("notification_id"));
        assertNotNull(state.get("delivered_at"));
        assertNull(state.get("dispatch_owner"));
        assertNull(state.get("dispatch_lease_until"));
        assertNull(state.get("next_attempt_at"));

        List<Map<String, Object>> notifications = jdbc.queryForList("""
                SELECT user_id, type, target_type, target_id, title, content
                FROM user_notification WHERE target_type = 'REPORT' AND target_id = ?
                """, jobId);
        assertEquals(1, notifications.size());
        Map<String, Object> notice = notifications.get(0);
        assertEquals(userId, ((Number) notice.get("user_id")).longValue());
        assertEquals("REPORT_READY", notice.get("type"));
        assertEquals("REPORT", notice.get("target_type"));
        assertEquals(jobId, ((Number) notice.get("target_id")).longValue());
        String content = String.valueOf(notice.get("content"));
        assertFalse(content.contains("<"));
        String studentNickname = jdbc.queryForObject(
                "SELECT nickname FROM user WHERE id = ?", String.class, studentId);
        assertFalse(content.contains(studentNickname));
        assertEquals(0, count("SELECT COUNT(*) FROM report_delivery WHERE job_id = ? AND channel IN ('EMAIL','SMS','WECHAT')", jobId));
    }

    @Test
    void preferenceOffSkipsWithoutNotification() {
        makeDispatchableJob("GENERATED");
        Long intentId = stageReady(1L);
        setPreferences("{\"notifications\":{\"interactions\":true,\"reports\":false}}");

        assertFalse(dispatcher.dispatchIntent(intentId));
        Map<String, Object> state = intentState(intentId);
        assertEquals("SKIPPED_PREFERENCE", state.get("status"));
        assertEquals(1, ((Number) state.get("attempts")).intValue());
        assertEquals("REPORT_PREFERENCES_DISABLED", state.get("last_error_code"));
        assertNull(state.get("next_attempt_at"));
        assertNull(state.get("dispatch_owner"));
        assertNull(state.get("dispatch_lease_until"));
        assertEquals(0, count("SELECT COUNT(*) FROM user_notification WHERE target_type = 'REPORT' AND target_id = ?", jobId));
    }

    @Test
    void revokedAuthorizationSkipsPermissionWithoutNotification() {
        makeDispatchableJob("GENERATED");
        Long intentId = stageReady(1L);
        Long replacement = insertUser(ENABLED_PREFERENCES);
        jdbc.update("UPDATE report_job SET active_requester_id = ? WHERE id = ?", replacement, jobId);

        assertFalse(dispatcher.dispatchIntent(intentId));
        Map<String, Object> state = intentState(intentId);
        assertEquals("SKIPPED_PERMISSION", state.get("status"));
        assertEquals(0, ((Number) state.get("attempts")).intValue());
        assertEquals("AUTH_REVOKED", state.get("last_error_code"));
        assertEquals(0, count("SELECT COUNT(*) FROM user_notification WHERE target_type = 'REPORT' AND target_id = ?", jobId));
    }

    @Test
    void revokedAuthorizationAfterClaimSkipsPermissionAtDelivery() {
        makeDispatchableJob("GENERATED");
        Long intentId = stageReady(1L);
        String owner = UUID.randomUUID().toString();
        claimDirectly(intentId, owner, 1, 60);
        Long replacement = insertUser(ENABLED_PREFERENCES);
        jdbc.update("UPDATE report_job SET active_requester_id = ? WHERE id = ?", replacement, jobId);

        ReportNotificationClaim claim = new ReportNotificationClaim(intentId, "REPORT_READY", jobId,
                classId, studentId, userId, 1L, 1L, 1L, owner, 1, 5);
        assertEquals(ReportNotificationDispatchRepository.DeliveryOutcome.SKIPPED,
                dispatchRepository.deliver(claim));
        Map<String, Object> state = intentState(intentId);
        assertEquals("SKIPPED_PERMISSION", state.get("status"));
        assertEquals(1, ((Number) state.get("attempts")).intValue());
        assertEquals("AUTH_REVOKED", state.get("last_error_code"));
        assertEquals(0, count("SELECT COUNT(*) FROM user_notification WHERE target_type = 'REPORT' AND target_id = ?", jobId));
    }

    @Test
    void staleTerminalIsSupersededWithoutNotification() {
        makeDispatchableJob("GENERATED");
        Long intentId = stageReady(1L);
        jdbc.update("UPDATE report_job SET terminal_revision = 2 WHERE id = ?", jobId);

        assertFalse(dispatcher.dispatchIntent(intentId));
        Map<String, Object> state = intentState(intentId);
        assertEquals("SKIPPED_SUPERSEDED", state.get("status"));
        assertEquals(0, ((Number) state.get("attempts")).intValue());
        assertEquals("STALE_TERMINAL", state.get("last_error_code"));
        assertEquals(0, count("SELECT COUNT(*) FROM user_notification WHERE target_type = 'REPORT' AND target_id = ?", jobId));
    }

    @Test
    void transientPreferenceFailureBacksOffThenDeliversOnNextAttempt() {
        makeDispatchableJob("GENERATED");
        Long intentId = stageReady(1L);
        setPreferences(MALFORMED_PREFERENCES);

        assertFalse(dispatcher.dispatchIntent(intentId));
        Map<String, Object> failed = intentState(intentId);
        assertEquals("PENDING", failed.get("status"));
        assertEquals(1, ((Number) failed.get("attempts")).intValue());
        assertEquals("PREFERENCE_READ_FAILED", failed.get("last_error_code"));
        assertEquals(30, backoffSeconds(intentId));

        setPreferences(ENABLED_PREFERENCES);
        makeIntentDue(intentId);
        assertTrue(dispatcher.dispatchIntent(intentId));
        Map<String, Object> delivered = intentState(intentId);
        assertEquals("DELIVERED", delivered.get("status"));
        assertEquals(2, ((Number) delivered.get("attempts")).intValue());
        assertEquals(1, count("SELECT COUNT(*) FROM user_notification WHERE target_type = 'REPORT' AND target_id = ?", jobId));
    }

    @Test
    void fiveDispatchFailuresFollowBackoffSequenceAndConvergeToFailed() {
        makeDispatchableJob("GENERATED");
        Long intentId = stageReady(1L);
        setPreferences(MALFORMED_PREFERENCES);
        int[] expectedBackoff = {30, 120, 600, 1800};

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertFalse(dispatcher.dispatchIntent(intentId));
            Map<String, Object> state = intentState(intentId);
            assertEquals(attempt, ((Number) state.get("attempts")).intValue());
            if (attempt < 5) {
                assertEquals("PENDING", state.get("status"));
                assertEquals("PREFERENCE_READ_FAILED", state.get("last_error_code"));
                assertEquals(expectedBackoff[attempt - 1], backoffSeconds(intentId));
                makeIntentDue(intentId);
            } else {
                assertEquals("FAILED", state.get("status"));
                assertEquals(5, ((Number) state.get("max_attempts")).intValue());
                assertNull(state.get("next_attempt_at"));
                assertNull(state.get("dispatch_owner"));
                assertNull(state.get("dispatch_lease_until"));
            }
        }
        assertEquals(0, count("SELECT COUNT(*) FROM user_notification WHERE target_type = 'REPORT' AND target_id = ?", jobId));
    }

    @Test
    void expiredDispatchLeaseRecoversOrFailsByPersistedAttempts() {
        makeDispatchableJob("GENERATED");
        Long intentId = stageReady(1L);

        String firstOwner = UUID.randomUUID().toString();
        jdbc.update("""
                UPDATE report_notification_intent
                SET status = 'DISPATCHING', attempts = 1, dispatch_generation = 1,
                    dispatch_owner = ?, next_attempt_at = NULL,
                    dispatch_lease_until = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)
                WHERE id = ?
                """, firstOwner, intentId);
        assertFalse(dispatcher.dispatchIntent(intentId));
        Map<String, Object> recovered = intentState(intentId);
        assertEquals("PENDING", recovered.get("status"));
        assertEquals(1, ((Number) recovered.get("attempts")).intValue());
        assertEquals("DISPATCH_LEASE_EXPIRED", recovered.get("last_error_code"));
        assertEquals(30, backoffSeconds(intentId));
        assertNull(recovered.get("dispatch_owner"));
        assertNull(recovered.get("dispatch_lease_until"));

        String exhaustedOwner = UUID.randomUUID().toString();
        jdbc.update("""
                UPDATE report_notification_intent
                SET status = 'DISPATCHING', attempts = 5, max_attempts = 5, dispatch_generation = 5,
                    dispatch_owner = ?, next_attempt_at = NULL,
                    dispatch_lease_until = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)
                WHERE id = ?
                """, exhaustedOwner, intentId);
        assertFalse(dispatcher.dispatchIntent(intentId));
        Map<String, Object> exhausted = intentState(intentId);
        assertEquals("FAILED", exhausted.get("status"));
        assertEquals(5, ((Number) exhausted.get("attempts")).intValue());
        assertEquals("ATTEMPTS_EXHAUSTED", exhausted.get("last_error_code"));
        assertNull(exhausted.get("dispatch_owner"));
        assertEquals(0, count("SELECT COUNT(*) FROM user_notification WHERE target_type = 'REPORT' AND target_id = ?", jobId));
    }

    @Test
    void adminQueueIsAdminOnlyAndRedriveExtendsBudgetExactlyOnce() throws Exception {
        makeDispatchableJob("GENERATED");
        Long intentId = stageReady(1L);
        setPreferences(MALFORMED_PREFERENCES);
        exhaustDispatch(intentId);
        assertEquals("FAILED", intentState(intentId).get("status"));
        setPreferences(ENABLED_PREFERENCES);

        mockMvc.perform(get("/admin/report-notification-intents")
                        .with(authentication(authenticationFor(userId, "ROLE_TEACHER", (byte) 2))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/report-notification-intents/" + intentId + "/redrive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(redriveBody(REDRIVE_REASON).getBytes(StandardCharsets.UTF_8))
                        .with(authentication(authenticationFor(userId, "ROLE_TEACHER", (byte) 2))))
                .andExpect(status().isForbidden());

        MvcResult listResult = mockMvc.perform(get("/admin/report-notification-intents")
                        .param("status", "FAILED")
                        .param("jobId", jobId.toString())
                        .with(authentication(authenticationFor(userId, "ROLE_ADMIN", (byte) 3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].intentId").value(intentId.intValue()))
                .andExpect(jsonPath("$.data.items[0].eventKind").value("REPORT_READY"))
                .andExpect(jsonPath("$.data.items[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data.items[0].attempts").value(5))
                .andExpect(jsonPath("$.data.items[0].maxAttempts").value(5))
                .andExpect(jsonPath("$.data.items[0].lastErrorCode").value("PREFERENCE_READ_FAILED"))
                .andReturn();
        String listBody = listResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String studentNickname = jdbc.queryForObject(
                "SELECT nickname FROM user WHERE id = ?", String.class, studentId);
        assertFalse(listBody.contains("<html"));
        assertFalse(listBody.contains(studentNickname));

        mockMvc.perform(post("/admin/report-notification-intents/" + intentId + "/redrive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(redriveBody("短原因").getBytes(StandardCharsets.UTF_8))
                        .with(authentication(authenticationFor(userId, "ROLE_ADMIN", (byte) 3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.data.errorCode").value("REASON_INVALID"));

        mockMvc.perform(post("/admin/report-notification-intents/" + intentId + "/redrive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(redriveBody(REDRIVE_REASON).getBytes(StandardCharsets.UTF_8))
                        .with(authentication(authenticationFor(userId, "ROLE_ADMIN", (byte) 3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.attempts").value(5))
                .andExpect(jsonPath("$.data.maxAttempts").value(10))
                .andExpect(jsonPath("$.data.redriveRound").value(1));
        Map<String, Object> afterRedrive = intentState(intentId);
        assertEquals("PREFERENCE_READ_FAILED", afterRedrive.get("last_error_code"));
        assertEquals(5, ((Number) afterRedrive.get("attempts")).intValue());
        assertEquals(10, ((Number) afterRedrive.get("max_attempts")).intValue());
        assertEquals(1, ((Number) afterRedrive.get("redrive_round")).intValue());
        assertEquals(userId, ((Number) afterRedrive.get("redrive_admin_id")).longValue());
        assertEquals(REDRIVE_REASON, afterRedrive.get("redrive_reason"));
        assertNotNull(afterRedrive.get("redriven_at"));

        mockMvc.perform(post("/admin/report-notification-intents/" + intentId + "/redrive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(redriveBody(REDRIVE_REASON).getBytes(StandardCharsets.UTF_8))
                        .with(authentication(authenticationFor(userId, "ROLE_ADMIN", (byte) 3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.data.errorCode").value("REDRIVE_ALREADY_USED"));
        assertEquals(1, ((Number) intentState(intentId).get("redrive_round")).intValue());

        setPreferences(MALFORMED_PREFERENCES);
        int[] expectedBackoff = {30, 120, 600, 1800};
        for (int attempt = 6; attempt <= 10; attempt++) {
            assertFalse(dispatcher.dispatchIntent(intentId));
            Map<String, Object> state = intentState(intentId);
            assertEquals(attempt, ((Number) state.get("attempts")).intValue());
            if (attempt < 10) {
                assertEquals("PENDING", state.get("status"));
                assertEquals(expectedBackoff[attempt - 6], backoffSeconds(intentId));
                makeIntentDue(intentId);
            } else {
                assertEquals("FAILED", state.get("status"));
                assertEquals(10, ((Number) state.get("max_attempts")).intValue());
            }
        }
        assertEquals(0, count("SELECT COUNT(*) FROM user_notification WHERE target_type = 'REPORT' AND target_id = ?", jobId));
    }

    @Test
    void adminRedriveRejectsDeliveredSkippedExpiredAndSupersededIntents() {
        makeDispatchableJob("GENERATED");
        Long deliveredIntent = stageReady(1L);
        assertTrue(dispatcher.dispatchIntent(deliveredIntent));
        assertEquals("REDRIVE_NOT_FAILED",
                adminService.redrive(deliveredIntent, REDRIVE_REASON, userId).errorCode());

        jdbc.update("UPDATE report_job SET status = 'FAILED', terminal_revision = 2 WHERE id = ?", jobId);
        Long failedIntent = stageFailed(2L, "EMPTY_RENDER");
        setPreferences(MALFORMED_PREFERENCES);
        exhaustDispatch(failedIntent);
        assertEquals("FAILED", intentState(failedIntent).get("status"));

        jdbc.update("UPDATE report_notification_intent SET status = 'SKIPPED_PREFERENCE' WHERE id = ?", failedIntent);
        assertEquals("REDRIVE_NOT_FAILED",
                adminService.redrive(failedIntent, REDRIVE_REASON, userId).errorCode());
        jdbc.update("UPDATE report_notification_intent SET status = 'FAILED' WHERE id = ?", failedIntent);

        jdbc.update("UPDATE report_job SET create_time = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 13 MONTH) WHERE id = ?", jobId);
        assertEquals("REDRIVE_EXPIRED",
                adminService.redrive(failedIntent, REDRIVE_REASON, userId).errorCode());

        jdbc.update("UPDATE report_job SET create_time = CURRENT_TIMESTAMP(3), terminal_revision = 3 WHERE id = ?", jobId);
        assertEquals("REDRIVE_SUPERSEDED",
                adminService.redrive(failedIntent, REDRIVE_REASON, userId).errorCode());
    }

    private ReportIntentCommand readyCommand(Long recipientId, Long revision) {
        return new ReportIntentCommand(jobId, userId + 1, userId, recipientId, 1L, revision, null);
    }

    private ReportIntentCommand failedCommand(Long recipientId, Long revision, String errorCode) {
        return new ReportIntentCommand(jobId, userId + 1, userId, recipientId, 1L, revision, errorCode);
    }

    private void makeDispatchableJob(String status) {
        classId = insertClass(userId);
        studentId = insertUser(ENABLED_PREFERENCES);
        jdbc.update("INSERT INTO class_student(class_id, student_id) VALUES (?, ?)", classId, studentId);
        jdbc.update("""
                        UPDATE report_job
                        SET class_id = ?, student_id = ?, status = ?, active_requester_id = ?,
                            generation_no = 1, terminal_revision = 1,
                            create_time = CURRENT_TIMESTAMP(3), update_time = CURRENT_TIMESTAMP(3)
                        WHERE id = ?
                        """, classId, studentId, status, userId, jobId);
    }

    private Long stageReady(Long revision) {
        transactions.executeWithoutResult(status -> intents.stageReadyIntent(
                new ReportIntentCommand(jobId, classId, studentId, userId, 1L, revision, null)));
        return intentId("REPORT_READY");
    }

    private void restageReady(Long revision) {
        transactions.executeWithoutResult(status -> intents.stageReadyIntent(
                new ReportIntentCommand(jobId, classId, studentId, userId, 1L, revision, null)));
    }

    private Long stageFailed(Long revision, String errorCode) {
        transactions.executeWithoutResult(status -> intents.stageFailedIntent(
                new ReportIntentCommand(jobId, classId, studentId, userId, 1L, revision, errorCode)));
        return intentId("REPORT_FAILED");
    }

    private Long intentId(String eventKind) {
        return jdbc.queryForObject("""
                SELECT id FROM report_notification_intent WHERE job_id = ? AND event_kind = ?
                """, Long.class, jobId, eventKind);
    }

    private Map<String, Object> intentState(Long intentId) {
        return jdbc.queryForMap("""
                SELECT status, attempts, max_attempts, redrive_round, next_attempt_at,
                       last_error_code, notification_id, delivered_at, dispatch_owner,
                       dispatch_lease_until, redrive_admin_id, redrive_reason, redriven_at
                FROM report_notification_intent WHERE id = ?
                """, intentId);
    }

    private void setPreferences(String preferences) {
        jdbc.update("UPDATE user SET preferences = ? WHERE id = ?", preferences, userId);
    }

    private void makeIntentDue(Long intentId) {
        jdbc.update("UPDATE report_notification_intent SET next_attempt_at = CURRENT_TIMESTAMP(3) WHERE id = ?", intentId);
    }

    private int backoffSeconds(Long intentId) {
        Integer seconds = jdbc.queryForObject("""
                SELECT TIMESTAMPDIFF(SECOND, updated_at, next_attempt_at)
                FROM report_notification_intent WHERE id = ?
                """, Integer.class, intentId);
        return seconds == null ? -1 : seconds;
    }

    private void exhaustDispatch(Long intentId) {
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertFalse(dispatcher.dispatchIntent(intentId));
            if (attempt < 5) makeIntentDue(intentId);
        }
    }

    private void claimDirectly(Long intentId, String owner, int attempts, int leaseSeconds) {
        jdbc.update("""
                UPDATE report_notification_intent
                SET status = 'DISPATCHING', attempts = ?, dispatch_generation = ?,
                    dispatch_owner = ?, next_attempt_at = NULL,
                    dispatch_lease_until = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL ? SECOND)
                WHERE id = ?
                """, attempts, attempts, owner, leaseSeconds, intentId);
    }

    private Long insertClass(Long teacherId) {
        String name = "rni-class-" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO class(name, teacher_id, student_count) VALUES (?, ?, 1)", name, teacherId);
        Long id = jdbc.queryForObject("SELECT id FROM class WHERE name = ?", Long.class, name);
        createdClasses.add(id);
        return id;
    }

    private Long insertNotification(Long recipientId, String type, Long targetId) {
        jdbc.update("""
                INSERT INTO user_notification (user_id, actor_id, type, target_type, target_id, title, content)
                VALUES (?, NULL, ?, 'REPORT', ?, '测试提醒', '合成验收数据')
                """, recipientId, type, targetId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private Long insertUser(String preferences) {
        String username = "rni_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO user(username, password, nickname, role, preferences) VALUES (?, 'test-only', ?, 2, ?)",
                username, username, preferences);
        Long id = jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
        createdUsers.add(id);
        return id;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static Authentication authenticationFor(Long id, String authority, byte role) {
        User user = new User();
        user.setId(id);
        user.setUsername("rni-auth-" + id);
        user.setRole(role);
        LoginUser loginUser = new LoginUser(user, List.of(new SimpleGrantedAuthority(authority)));
        return new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities());
    }

    private static String redriveBody(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }
}
