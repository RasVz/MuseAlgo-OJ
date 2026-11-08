package com.musealgo.oj.controller;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import com.musealgo.oj.OjApplication;
import com.musealgo.oj.analytics.application.ReportAccessService;
import com.musealgo.oj.analytics.application.ReportApplicationService;
import com.musealgo.oj.analytics.application.ReportJobException;
import com.musealgo.oj.analytics.application.ReportJobExecution;
import com.musealgo.oj.analytics.application.ReportJobLifecycleService;
import com.musealgo.oj.analytics.application.ReportJobService;
import com.musealgo.oj.analytics.domain.StudentProfile;
import com.musealgo.oj.analytics.dto.BatchReportItem;
import com.musealgo.oj.analytics.dto.ReportJobVO;
import com.musealgo.oj.analytics.dto.ReportNotificationStatusVO;
import com.musealgo.oj.analytics.report.ReportResult;
import com.musealgo.oj.common.exception.ForbiddenException;
import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.system.entity.LoginUser;
import com.musealgo.oj.system.entity.User;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

/**
 * Real MySQL report lifecycle acceptance. These tests create only synthetic users/classes and
 * disable both pollers in the parent JVM; the child-JVM test explicitly enables only the report
 * generation worker while RabbitMQ and notice dispatch remain off.
 */
@SpringBootTest(classes = OjApplication.class, properties = {
        "oj.messaging.rabbitmq-enabled=false",
        "oj.judge.worker.enabled=false",
        "report.worker.enabled=false",
        "report.notification.worker-enabled=false",
        "report.weekly-enabled=false",
        "report.retention-cleanup-enabled=false"
})
class ReportJobIT {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 10, 12);
    private static final LocalDate PERIOD_END = PERIOD_START.plusDays(6);

    @Autowired private ReportJobService reportJobService;
    @Autowired private ReportAccessService reportAccessService;
    @Autowired private ReportJobLifecycleService lifecycleService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.musealgo.oj.analytics.application.ReportRetentionTask retention;
    @Autowired private com.musealgo.oj.analytics.controller.ProfileController profileController;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private ReportApplicationService reports;

    private Long teacherId;
    private Long successorTeacherId;
    private Long otherTeacherId;
    private Long studentId;
    private Long otherStudentId;
    private Long classId;
    private final Set<Long> jobIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<Long> syntheticUserIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<Long> syntheticClassIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @AfterEach
    void cleanup() {
        for (Long id : jobIds) {
            Long snapshotId = jdbc.query("SELECT snapshot_id FROM report_job WHERE id=?", rs ->
                    rs.next() ? rs.getObject(1, Long.class) : null, id);
            // Synthetic-fixture teardown only. Production outbox and notification writes stay in notice.
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
        syntheticClassIds.clear();
        syntheticUserIds.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void jobsSingleBatchAndPreviewCompatibilityShareOneEnqueueOnlyJob() {
        fixture("entrypoints");
        login(teacherId, "ROLE_TEACHER");

        Result<ReportResult> single = profileController.generateWeeklyReport(
                studentId, classId, PERIOD_START, PERIOD_END);
        ReportResult legacySingle = single.getData();
        assertEquals(200, single.getCode());
        assertTrue(legacySingle.accepted());
        assertEquals("PENDING", legacySingle.status());
        assertNotNull(legacySingle.jobId());
        assertNull(legacySingle.snapshotId());
        assertNull(legacySingle.html());
        assertFalse(legacySingle.pdfGenerated());
        register(legacySingle.jobId());

        var batchRequest = new com.musealgo.oj.analytics.dto.BatchReportRequest();
        batchRequest.setClassId(classId);
        batchRequest.setStudentIds(List.of(studentId));
        Result<List<BatchReportItem>> batch = profileController.generateBatchReports(
                batchRequest, PERIOD_START, PERIOD_END);
        BatchReportItem legacyBatch = batch.getData().get(0);
        assertEquals(legacySingle.jobId(), legacyBatch.getJobId());
        assertEquals("PENDING", legacyBatch.getStatus());
        assertTrue(legacyBatch.isAccepted());
        assertFalse(legacyBatch.isSuccess());
        assertNull(legacyBatch.getSnapshotId());
        assertNull(legacyBatch.getHtml());

        ReportJobVO jobsApi = reportJobService.requestReport(studentId, classId, PERIOD_START, PERIOD_END);
        assertEquals(legacySingle.jobId(), jobsApi.id());
        assertEquals("PENDING", jobsApi.status());
        assertEquals(0, jobsApi.attempts());
        assertNull(jobsApi.html());
        assertEquals(1, count("SELECT COUNT(*) FROM report_job WHERE id=?", jobsApi.id()));
        assertEquals(0, count("SELECT COUNT(*) FROM report_job_attempt WHERE job_id=?", jobsApi.id()));
        assertEquals(0, count("SELECT COUNT(*) FROM student_profile_snapshot WHERE student_id=? AND period_start=?",
                studentId, PERIOD_START));
        assertEquals(0, count("SELECT COUNT(*) FROM report_delivery WHERE job_id=?", jobsApi.id()));

        var pendingPreview = profileController.previewReportHtml(studentId, classId, PERIOD_START, PERIOD_END);
        assertEquals(409, pendingPreview.getStatusCode().value());
        var missingPreview = profileController.previewReportHtml(
                studentId, classId, PERIOD_START.minusWeeks(1), PERIOD_END.minusWeeks(1));
        assertEquals(404, missingPreview.getStatusCode().value());
        assertThrows(ForbiddenException.class, () -> reportJobService.requestReport(
                otherStudentId, classId, PERIOD_START, PERIOD_END));
        verifyNeverRendered(studentId);
    }

    @Test
    void generatedFinalizerWritesSnapshotAvailabilityAttemptAndReadyIntentAtomically() throws Exception {
        fixture("finalizer");
        ReportJobVO accepted = enqueue();
        ReportJobExecution execution = lifecycleService.claimNext();
        assertNotNull(execution);
        assertEquals(accepted.id(), execution.jobId());
        assertEquals(1, execution.attemptNo());

        assertTrue(lifecycleService.markFinalizing(execution));
        assertTrue(lifecycleService.finalizeGenerated(execution, profile(), "<html>generated</html>"));

        ReportJobVO generated = reportJobService.getReport(accepted.id());
        assertEquals("GENERATED", generated.status());
        assertEquals(1, generated.terminalRevision());
        assertEquals(1, generated.attempts());
        assertNotNull(generated.snapshotId());
        assertEquals("<html>generated</html>", generated.html());
        assertEquals(1, count("SELECT COUNT(*) FROM report_delivery WHERE job_id=? AND channel='IN_APP' " +
                "AND status='DELIVERED'", accepted.id()));
        assertEquals("GENERATED", jdbc.queryForObject(
                "SELECT outcome FROM report_job_attempt WHERE job_id=? AND attempt_no=1", String.class, accepted.id()));

        String json = jdbc.queryForObject("SELECT snapshot_json FROM student_profile_snapshot WHERE id=?",
                String.class, generated.snapshotId());
        assertNotNull(json);
        com.fasterxml.jackson.databind.JsonNode snapshotJson =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertEquals(PERIOD_START.toString(), snapshotJson.path("periodStart").asText(), json);
        assertEquals(PERIOD_END.toString(), snapshotJson.path("periodEnd").asText(), json);

        ReportNotificationStatusVO notification = reportJobService.getNotificationStatus(accepted.id());
        assertTrue(notification.centerAvailable());
        assertEquals(1, notification.currentTerminalRevision());
        assertEquals(1, notification.intents().size());
        assertEquals("REPORT_READY", notification.intents().get(0).eventKind());
        assertEquals("PENDING", notification.intents().get(0).status());
        assertFalse(notification.intents().get(0).isRead());
    }

    @Test
    void finalizerRechecksMembershipAndEmptyHtmlCreatesFailedIntentWithoutSnapshot() {
        fixture("finish-auth");
        ReportJobVO accepted = enqueue();
        ReportJobExecution revokedExecution = lifecycleService.claimNext();
        jdbc.update("DELETE FROM class_student WHERE class_id=? AND student_id=?", classId, studentId);
        assertTrue(lifecycleService.finalizeGenerated(revokedExecution, profile(), "<html>stale</html>"));
        ReportJobVO revoked = reportJobService.getReport(accepted.id());
        assertEquals("FAILED", revoked.status());
        assertEquals("AUTH_REVOKED", revoked.lastErrorCode());
        assertNull(revoked.snapshotId());
        assertNull(revoked.html());
        ReportNotificationStatusVO failureNotice = reportJobService.getNotificationStatus(accepted.id());
        assertFalse(failureNotice.centerAvailable());
        assertEquals("REPORT_FAILED", failureNotice.intents().get(0).eventKind());

        fixture("empty-render");
        ReportJobVO emptyAccepted = enqueue();
        ReportJobExecution emptyExecution = lifecycleService.claimNext();
        assertTrue(lifecycleService.finalizeGenerated(emptyExecution, profile(), " \n "));
        ReportJobVO empty = reportJobService.getReport(emptyAccepted.id());
        assertEquals("FAILED", empty.status());
        assertEquals("EMPTY_RENDER", empty.lastErrorCode());
        assertNull(empty.snapshotId());
        assertEquals("REPORT_FAILED", reportJobService.getNotificationStatus(emptyAccepted.id())
                .intents().get(0).eventKind());
        assertEquals(0, count("SELECT COUNT(*) FROM report_notification_intent WHERE job_id=? AND event_kind='REPORT_READY'",
                emptyAccepted.id()));
    }

    @Test
    void automaticAndExplicitRetriesShareThreeAttemptBudgetAndDuplicateEnqueueDoesNotReviveFailure() {
        fixture("budget");
        ReportJobVO accepted = enqueue();
        for (int expectedAttempt = 1; expectedAttempt <= 3; expectedAttempt++) {
            if (expectedAttempt > 1) {
                jdbc.update("UPDATE report_job SET next_attempt_at=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) " +
                        "WHERE id=?", accepted.id());
            }
            ReportJobExecution execution = lifecycleService.claimNext();
            assertNotNull(execution);
            assertEquals(expectedAttempt, execution.attemptNo());
            assertTrue(lifecycleService.recordGenerationFailure(execution, "GENERATOR_UNAVAILABLE"));
        }

        ReportJobVO failed = reportJobService.getReport(accepted.id());
        assertEquals("FAILED", failed.status());
        assertEquals(3, failed.attempts());
        assertFalse(failed.canRetry());
        assertEquals(3, count("SELECT COUNT(*) FROM report_job_attempt WHERE job_id=?", accepted.id()));
        assertThrows(ReportJobException.class, () -> reportJobService.retryReport(accepted.id()));
        ReportJobVO repeated = reportJobService.requestReport(studentId, classId, PERIOD_START, PERIOD_END);
        assertEquals("FAILED", repeated.status());
        assertEquals(3, repeated.attempts());
        assertEquals(1, reportJobService.getNotificationStatus(accepted.id()).intents().size());
        assertEquals("REPORT_FAILED", reportJobService.getNotificationStatus(accepted.id()).intents().get(0).eventKind());
    }

    @Test
    void authorizedSuccessorCanExplicitlyRetryAndRebindRequesterWithoutResettingBudget() {
        fixture("successor");
        successorTeacherId = insertUser("TEACHER", "rpt_successor_");
        ReportJobVO accepted = enqueue();
        ReportJobExecution execution = lifecycleService.claimNext();
        jdbc.update("UPDATE class SET teacher_id=? WHERE id=?", successorTeacherId, classId);
        assertTrue(lifecycleService.recordGenerationFailure(execution, "GENERATOR_UNAVAILABLE"));
        login(successorTeacherId, "ROLE_TEACHER");
        ReportJobVO failed = reportJobService.getReport(accepted.id());
        assertEquals("AUTH_REVOKED", failed.lastErrorCode());
        assertTrue(failed.canRetry());

        ReportJobVO retried = reportJobService.retryReport(accepted.id());
        assertEquals("PENDING", retried.status());
        assertEquals(successorTeacherId, retried.activeRequesterId());
        assertEquals(1, retried.attempts());
        ReportJobVO duplicateRetry = reportJobService.retryReport(accepted.id());
        assertEquals("PENDING", duplicateRetry.status());
        assertEquals(1, duplicateRetry.attempts());
        assertEquals(1, count("SELECT COUNT(*) FROM report_job_attempt WHERE job_id=?", accepted.id()));
    }

    @Test
    void concurrentNaturalKeyRequestsAndClaimsUsePreLockBarrierAndSingleBudget() throws Exception {
        fixture("concurrency");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            var request = (java.util.concurrent.Callable<ReportJobVO>) () -> {
                login(teacherId, "ROLE_TEACHER");
                ready.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                try {
                    return reportJobService.requestReport(studentId, classId, PERIOD_START, PERIOD_END);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            };
            var first = pool.submit(request);
            var second = pool.submit(request);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            release.countDown();
            ReportJobVO a = first.get(30, TimeUnit.SECONDS);
            ReportJobVO b = second.get(30, TimeUnit.SECONDS);
            register(a.id());
            assertEquals(a.id(), b.id());
            assertEquals(1, count("SELECT COUNT(*) FROM report_job WHERE student_id=? AND class_id=? " +
                    "AND period_start=? AND period_end=?", studentId, classId, PERIOD_START, PERIOD_END));
            assertEquals(0, a.attempts());
        } finally {
            pool.shutdownNow();
        }

        ExecutorService claimers = Executors.newFixedThreadPool(2);
        CountDownLatch claimReady = new CountDownLatch(2);
        CountDownLatch claimRelease = new CountDownLatch(1);
        try {
            var claim = (java.util.concurrent.Callable<ReportJobExecution>) () -> {
                claimReady.countDown();
                assertTrue(claimRelease.await(10, TimeUnit.SECONDS));
                return lifecycleService.claimNext();
            };
            var first = claimers.submit(claim);
            var second = claimers.submit(claim);
            assertTrue(claimReady.await(10, TimeUnit.SECONDS));
            claimRelease.countDown();
            ReportJobExecution a = first.get(30, TimeUnit.SECONDS);
            ReportJobExecution b = second.get(30, TimeUnit.SECONDS);
            assertNotEquals(a == null, b == null);
            ReportJobExecution winner = a == null ? b : a;
            assertEquals(1, winner.attemptNo());
            assertEquals(1, count("SELECT COUNT(*) FROM report_job_attempt WHERE job_id=?", winner.jobId()));
            assertEquals(1, count("SELECT attempts FROM report_job WHERE id=?", winner.jobId()));
        } finally {
            claimers.shutdownNow();
        }
    }

    @Test
    void concurrentExplicitRetryIsSerializedAndDoesNotCreateAnotherAttempt() throws Exception {
        fixture("retry-race");
        ReportJobVO accepted = enqueue();
        ReportJobExecution execution = lifecycleService.claimNext();
        jdbc.update("UPDATE class_student SET student_id=? WHERE class_id=? AND student_id=?",
                otherStudentId, classId, studentId);
        assertTrue(lifecycleService.recordGenerationFailure(execution, "GENERATOR_UNAVAILABLE"));
        assertEquals("AUTH_REVOKED", reportJobService.getReport(accepted.id()).lastErrorCode());
        jdbc.update("UPDATE class_student SET student_id=? WHERE class_id=? AND student_id=?",
                studentId, classId, otherStudentId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            var retry = (java.util.concurrent.Callable<ReportJobVO>) () -> {
                login(teacherId, "ROLE_TEACHER");
                ready.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                try {
                    return reportJobService.retryReport(accepted.id());
                } finally {
                    SecurityContextHolder.clearContext();
                }
            };
            var first = pool.submit(retry);
            var second = pool.submit(retry);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            release.countDown();
            assertEquals("PENDING", first.get(30, TimeUnit.SECONDS).status());
            assertEquals("PENDING", second.get(30, TimeUnit.SECONDS).status());
            assertEquals(1, count("SELECT attempts FROM report_job WHERE id=?", accepted.id()));
            assertEquals(1, count("SELECT COUNT(*) FROM report_job_attempt WHERE job_id=?", accepted.id()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void expiredLeaseCannotRenewOrWriteAndNextGenerationWins() {
        fixture("lease-fence");
        ReportJobVO accepted = enqueue();
        ReportJobExecution oldOwner = lifecycleService.claimNext();
        assertTrue(lifecycleService.heartbeat(oldOwner));
        jdbc.update("UPDATE report_job SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id=?",
                accepted.id());
        assertFalse(lifecycleService.heartbeat(oldOwner));
        assertFalse(lifecycleService.finalizeGenerated(oldOwner, profile(), "<html>stale</html>"));
        assertNull(jdbc.queryForObject("SELECT snapshot_id FROM report_job WHERE id=?", Long.class, accepted.id()));

        assertNull(lifecycleService.claimNext()); // expiry recovery commits a finite retry delay
        jdbc.update("UPDATE report_job SET next_attempt_at=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id=?",
                accepted.id());
        ReportJobExecution newOwner = lifecycleService.claimNext();
        assertNotNull(newOwner);
        assertEquals(2, newOwner.attemptNo());
        assertTrue(lifecycleService.markFinalizing(newOwner));
        assertTrue(lifecycleService.finalizeGenerated(newOwner, profile(), "<html>current</html>"));
        assertFalse(lifecycleService.heartbeat(oldOwner));
        assertFalse(lifecycleService.finalizeGenerated(oldOwner, profile(), "<html>late old owner</html>"));

        ReportJobVO finalJob = reportJobService.getReport(accepted.id());
        assertEquals("GENERATED", finalJob.status());
        assertEquals("<html>current</html>", finalJob.html());
        assertEquals(2, finalJob.generationNo());
        assertEquals(2, finalJob.attempts());
        assertEquals(1, finalJob.terminalRevision());
        assertEquals(1, count("SELECT COUNT(*) FROM report_notification_intent WHERE job_id=? AND event_kind='REPORT_READY'",
                accepted.id()));
    }

    @Test
    void thirdExpiredClaimBecomesFailedAndLegacyGenerationZeroCanStageFailure() {
        fixture("last-lease");
        ReportJobVO accepted = enqueue();
        for (int attemptNo = 1; attemptNo <= 3; attemptNo++) {
            if (attemptNo > 1) {
                jdbc.update("UPDATE report_job SET next_attempt_at=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) " +
                        "WHERE id=?", accepted.id());
            }
            ReportJobExecution execution = lifecycleService.claimNext();
            assertNotNull(execution);
            assertEquals(attemptNo, execution.attemptNo());
            if (attemptNo < 3) {
                assertTrue(lifecycleService.recordGenerationFailure(execution, "GENERATOR_UNAVAILABLE"));
            } else {
                jdbc.update("UPDATE report_job SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) " +
                        "WHERE id=?", accepted.id());
                assertNull(lifecycleService.claimNext());
            }
        }
        ReportJobVO exhausted = reportJobService.getReport(accepted.id());
        assertEquals("FAILED", exhausted.status());
        assertEquals(3, exhausted.attempts());
        assertEquals("ATTEMPTS_EXHAUSTED", exhausted.lastErrorCode());
        assertEquals(1, reportJobService.getNotificationStatus(accepted.id()).intents().size());
        assertEquals("REPORT_FAILED", reportJobService.getNotificationStatus(accepted.id()).intents().get(0).eventKind());

        fixture("legacy-generation-zero");
        ReportJobVO legacy = enqueue();
        jdbc.update("UPDATE report_job SET status='GENERATING',attempts=3,generation_no=0,lease_owner=NULL,lease_until=NULL " +
                "WHERE id=?", legacy.id());
        jdbc.update("UPDATE report_job SET requester_id=NULL,active_requester_id=NULL WHERE id=?", legacy.id());
        assertEquals(1, lifecycleService.recoverLegacyGenerating());
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM report_job WHERE id=?", String.class, legacy.id()));
        assertEquals(0L, jdbc.queryForObject("SELECT generation_no FROM report_job WHERE id=?", Long.class, legacy.id()));
        assertEquals(1, count("SELECT COUNT(*) FROM report_notification_intent WHERE job_id=? AND event_kind='REPORT_FAILED' " +
                "AND generation_no=0 AND status='SKIPPED_PERMISSION'", legacy.id()));
    }

    @Test
    void retentionSkipsPendingJobsAndExpiredGeneratingRowsRemainRecoverable() {
        fixture("retention-active");
        ReportJobVO pending = enqueue();
        jdbc.update("UPDATE report_job SET create_time=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 2 YEAR) WHERE id=?",
                pending.id());
        retention.cleanupExpiredReports();
        assertEquals(1, count("SELECT COUNT(*) FROM report_job WHERE id=?", pending.id()));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM report_job WHERE id=?", String.class, pending.id()));

        ReportJobExecution execution = lifecycleService.claimNext();
        assertNotNull(execution);
        jdbc.update("UPDATE report_job SET create_time=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 2 YEAR), " +
                "lease_until=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id=?", pending.id());
        retention.cleanupExpiredReports();
        assertEquals(1, count("SELECT COUNT(*) FROM report_job WHERE id=?", pending.id()));
    }

    @Test
    void disabledWorkerDoesNotUseHttpSecurityContextForLifecycleClaims() {
        fixture("empty-security-context");
        ReportJobVO accepted = enqueue();
        SecurityContextHolder.clearContext();
        ReportJobExecution execution = lifecycleService.claimNext();
        assertNotNull(execution);
        assertEquals(accepted.id(), execution.jobId());
        assertTrue(lifecycleService.recordGenerationFailure(execution, "EMPTY_RENDER"));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM report_job WHERE id=?", String.class, accepted.id()));
    }

    @Test
    void realChildJvmKillLeaseRecoveryAndRestartPreserveBudgetAndFenceOldOwner() throws Exception {
        fixture("child-restart");
        ReportJobVO accepted = enqueue();
        ChildJvm first = ChildJvm.start("block");
        ChildJvm second = null;
        try {
            assertTrue(first.renderEntered.await(45, TimeUnit.SECONDS), first.safeStatus());
            assertEquals("GENERATING", jdbc.queryForObject("SELECT status FROM report_job WHERE id=?", String.class, accepted.id()));
            assertEquals(1, count("SELECT COUNT(*) FROM report_job_attempt WHERE job_id=? AND outcome IS NULL",
                    accepted.id()));
            first.process.destroyForcibly();
            assertTrue(first.process.waitFor(20, TimeUnit.SECONDS), "first child JVM did not exit after forced kill");

            jdbc.update("UPDATE report_job SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id=?",
                    accepted.id());
            second = ChildJvm.start("complete");
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM report_job WHERE id=?", String.class,
                        accepted.id()));
            });
            jdbc.update("UPDATE report_job SET next_attempt_at=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) " +
                    "WHERE id=?", accepted.id());
            assertTrue(second.renderEntered.await(30, TimeUnit.SECONDS), second.safeStatus());
            Awaitility.await().atMost(Duration.ofSeconds(45)).untilAsserted(() ->
                    assertEquals("GENERATED", jdbc.queryForObject("SELECT status FROM report_job WHERE id=?",
                            String.class, accepted.id())));

            ReportJobVO generated = reportJobService.getReport(accepted.id());
            assertEquals(2, generated.attempts());
            assertEquals(2, generated.generationNo());
            assertNotNull(generated.snapshotId());
            assertEquals(2, count("SELECT COUNT(*) FROM report_job_attempt WHERE job_id=?", accepted.id()));
            assertEquals(1, count("SELECT COUNT(*) FROM report_delivery WHERE job_id=? AND channel='IN_APP' " +
                    "AND status='DELIVERED'", accepted.id()));
            assertEquals(1, count("SELECT COUNT(*) FROM report_notification_intent WHERE job_id=? " +
                    "AND event_kind='REPORT_READY' AND status='PENDING'", accepted.id()));
            assertEquals("GENERATED", jdbc.queryForObject("SELECT outcome FROM report_job_attempt " +
                    "WHERE job_id=? AND attempt_no=2", String.class, accepted.id()));
        } finally {
            first.stop();
            if (second != null) second.stop();
        }
    }

    private ReportJobVO enqueue() {
        login(teacherId, "ROLE_TEACHER");
        ReportJobVO result = reportJobService.requestReport(studentId, classId, PERIOD_START, PERIOD_END);
        register(result.id());
        return result;
    }

    private void fixture(String prefix) {
        // Some acceptance methods deliberately exercise two independent subjects/periods.
        // Tear down the previous synthetic fixture before replacing the cleanup identifiers.
        cleanup();
        teacherId = insertUser("TEACHER", "rpt_" + prefix + "_teacher_");
        studentId = insertUser("STUDENT", "rpt_" + prefix + "_student_");
        otherTeacherId = insertUser("TEACHER", "rpt_" + prefix + "_other_teacher_");
        otherStudentId = insertUser("STUDENT", "rpt_" + prefix + "_other_student_");
        classId = insertClass(teacherId);
        jdbc.update("INSERT INTO class_student(class_id,student_id) VALUES (?,?)", classId, studentId);
    }

    private void register(Long id) {
        if (id != null) jobIds.add(id);
    }

    private StudentProfile profile() {
        return new StudentProfile(studentId, "synthetic student", classId, "synthetic class",
                PERIOD_START, PERIOD_END, new com.musealgo.oj.api.profile.ProfileQuery.WeekOverview(0, 0, 0, 0),
                List.of(), Map.of(), Map.of(), List.of());
    }

    private void verifyNeverRendered(Long expectedStudentId) {
        Mockito.verify(reports, Mockito.never()).prepareWeeklyReportInternal(
                anyLong(), anyLong(), any(LocalDate.class), any(LocalDate.class));
        assertEquals(0, count("SELECT COUNT(*) FROM student_profile_snapshot WHERE student_id=?", expectedStudentId));
    }

    private void login(Long userId, String role) {
        User user = new User();
        user.setId(userId);
        user.setUsername("u" + userId);
        user.setNickname("u" + userId);
        user.setRole(Byte.valueOf("ROLE_ADMIN".equals(role) ? "3" : "2"));
        LoginUser loginUser = new LoginUser(user, List.of(new SimpleGrantedAuthority(role)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
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
        String name = "rpt-class-" + UUID.randomUUID();
        jdbc.update("INSERT INTO class(name,teacher_id,invite_code) VALUES (?,?,?)",
                name, ownerId, UUID.randomUUID().toString().substring(0, 8));
        Long id = jdbc.queryForObject("SELECT id FROM class WHERE name=?", Long.class, name);
        syntheticClassIds.add(id);
        return id;
    }

    /** Child process uses the real Spring worker and a test-only renderer barrier. */
    public static final class WorkerProbe {
        public static void main(String[] args) {
            new SpringApplicationBuilder(OjApplication.class, WorkerProbeConfiguration.class)
                    .web(WebApplicationType.NONE)
                    .run(args);
        }
    }

    @Configuration(proxyBeanMethods = false)
    public static class WorkerProbeConfiguration {
        @Bean
        @Primary
        ReportApplicationService workerProbeReportApplicationService(
                org.springframework.core.env.Environment environment) throws Exception {
            String mode = environment.getProperty("report.worker.probe.mode", "block");
            java.util.concurrent.atomic.AtomicInteger renderCount = new java.util.concurrent.atomic.AtomicInteger();
            ReportApplicationService service = Mockito.mock(ReportApplicationService.class);
            doAnswer(invocation -> {
                int renderNumber = renderCount.incrementAndGet();
                Long probeStudentId = invocation.getArgument(0);
                Long probeClassId = invocation.getArgument(1);
                LocalDate start = invocation.getArgument(2);
                LocalDate end = invocation.getArgument(3);
                System.out.println("RPT_PROBE_RENDER_ENTERED");
                System.out.flush();
                if ("block".equals(mode) || "hold".equals(mode) || ("overlap".equals(mode) && renderNumber == 1)) {
                    BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                    input.readLine(); // Parent releases or stops this JVM after checking the lease.
                    System.out.println("RPT_PROBE_RENDER_RELEASED");
                    System.out.flush();
                }
                StudentProfile profile = new StudentProfile(probeStudentId, "synthetic child student", probeClassId,
                        "synthetic child class", start, end,
                        new com.musealgo.oj.api.profile.ProfileQuery.WeekOverview(0, 0, 0, 0),
                        List.of(), Map.of(), Map.of(), List.of());
                return new ReportApplicationService.PreparedReport(profile, "<html>child report</html>");
            }).when(service).prepareWeeklyReportInternal(anyLong(), anyLong(), any(LocalDate.class), any(LocalDate.class));
            return service;
        }

        @Bean
        ApplicationRunner workerProbeProperties() {
            return args -> System.out.println("RPT_PROBE_CONTEXT_READY");
        }
    }

    private static final class ChildJvm {
        private final Process process;
        private final CountDownLatch renderEntered = new CountDownLatch(1);
        private final List<String> output = new CopyOnWriteArrayList<>();

        private ChildJvm(Process process) {
            this.process = process;
            Thread drain = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.add(line);
                        if (line.contains("RPT_PROBE_RENDER_ENTERED")) renderEntered.countDown();
                    }
                } catch (Exception ignored) {
                    // The parent waits on explicit markers or OS process exit; stream closure is expected on kill.
                }
            }, "rpt-child-stdout-reader");
            drain.setDaemon(true);
            drain.start();
        }

        private static ChildJvm start(String mode) throws Exception {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            List<String> command = List.of(java, "-Xmx256m", "-Duser.timezone=UTC",
                    "-Dreport.worker.probe.mode=" + mode, "-cp", classpath,
                    WorkerProbe.class.getName(),
                    "--spring.main.web-application-type=none",
                    "--spring.autoconfigure.exclude=com.github.xiaoymin.knife4j.spring.configuration.Knife4jAutoConfiguration",
                    "--spring.flyway.enabled=false",
                    "--oj.messaging.rabbitmq-enabled=false",
                    "--oj.judge.worker.enabled=false",
                    "--report.worker.enabled=true",
                    "--report.worker.threads=1",
                    "--report.worker.poll-interval-ms=100",
                    "--report.worker.watchdog-interval-ms=100",
                    "--report.worker.heartbeat-interval-ms=1000",
                    "--report.worker.recover-legacy-generating=true",
                    "--report.notification.worker-enabled=false",
                    "--report.weekly-enabled=false",
                    "--report.retention-cleanup-enabled=false");
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            return new ChildJvm(process);
        }

        private String safeStatus() {
            int from = Math.max(0, output.size() - 20);
            return "child JVM exited=" + !process.isAlive() + ", outputLines=" + output.size()
                    + ", tail=" + String.join(" | ", output.subList(from, output.size()));
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
