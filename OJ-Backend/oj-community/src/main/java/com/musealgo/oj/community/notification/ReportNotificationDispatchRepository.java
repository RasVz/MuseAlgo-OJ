package com.musealgo.oj.community.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.musealgo.oj.api.report.ReportNoticeContextQuery;
import com.musealgo.oj.api.report.ReportNoticeContextSnapshot;
import com.musealgo.oj.community.service.NotificationService;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Durable claim/delivery persistence for report notification intents.
 * <p>
 * Cross-domain lock order is report job -&gt; intent -&gt; recipient preference (user) row.
 * The preference share lock is held through notification insertion and transaction commit, so
 * preference changes serialize with both delivery and preference skips. Candidate
 * discovery therefore runs in its own short {@code SKIP LOCKED} transaction and releases those
 * locks before the per-intent claim revalidates under the required job -&gt; intent order.
 * </p>
 */
@Repository
@RequiredArgsConstructor
public class ReportNotificationDispatchRepository {

    private static final Pattern SAFE_ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final int[] BACKOFF_SECONDS = {30, 120, 600, 1800};
    private static final String READY_TITLE = "学习周报已生成";
    private static final String READY_CONTENT = "学习周报已生成，可前往报告中心查看。";
    private static final String FAILED_TITLE = "学习周报生成失败";
    private static final String FAILED_CONTENT = "学习周报生成未完成，可前往报告中心查看失败原因。";
    private static final String PENDING_DISCOVERY_SQL = """
            SELECT id, next_attempt_at AS due_at FROM report_notification_intent FORCE INDEX (ix_rni_pending_due)
            WHERE status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP(3))
            ORDER BY next_attempt_at, id LIMIT ? FOR UPDATE SKIP LOCKED
            """;
    private static final String EXPIRED_DISCOVERY_SQL = """
            SELECT id, dispatch_lease_until AS due_at FROM report_notification_intent FORCE INDEX (ix_rni_expired_lease)
            WHERE status = 'DISPATCHING' AND dispatch_lease_until <= CURRENT_TIMESTAMP(3)
            ORDER BY dispatch_lease_until, id LIMIT ? FOR UPDATE SKIP LOCKED
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ReportNoticeContextQuery noticeContextQuery;
    private final NotificationService notificationService;

    @Value("${report.notification.dispatch-lease-ttl-seconds:60}")
    private int dispatchLeaseTtlSeconds;

    /** Discovery only; the committed claim below is authoritative. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public List<Long> findDueIntentIds(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<DueIntent> pending = findDue(PENDING_DISCOVERY_SQL, safeLimit);
        List<DueIntent> expired = findDue(EXPIRED_DISCOVERY_SQL, safeLimit);
        return Stream.concat(pending.stream(), expired.stream())
                .sorted(Comparator.comparing(DueIntent::dueAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparingLong(DueIntent::id))
                .limit(safeLimit).map(DueIntent::id).toList();
    }

    private List<DueIntent> findDue(String sql, int limit) {
        return jdbcTemplate.query(sql, (rs, row) -> {
            var due = rs.getTimestamp("due_at");
            return new DueIntent(rs.getLong("id"), due == null ? null : due.toLocalDateTime());
        }, limit);
    }

    private record DueIntent(long id, LocalDateTime dueAt) {}

    /**
     * Commits one attempt or a terminal skip. The returned claim is the only authority to perform
     * delivery work; callers must not reuse it after its lease expires.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public ReportNotificationClaim claimIntent(Long intentId) {
        IntentRow identity = readIdentity(intentId);
        if (identity == null) return null;

        ReportNoticeContextSnapshot context = lockContext(identity);
        IntentRow row = lockIntent(intentId);
        if (row == null || !identity.sameIdentity(row)) return null;
        if (!row.due()) return null;

        String terminalSkip = terminalSkipReason(context);
        if (terminalSkip != null) {
            markSkipped(row, "SKIPPED_SUPERSEDED", terminalSkip);
            return null;
        }
        if (!context.recipientAuthorized()) {
            markSkipped(row, "SKIPPED_PERMISSION",
                    safeCode(context.authFailureCode(), "AUTH_REVOKED"));
            return null;
        }

        if ("DISPATCHING".equals(row.status())) {
            // Lease-expiry recovery: the prior claim already consumed its attempt.
            if (row.attempts() >= row.maxAttempts()) {
                markFailed(row, "ATTEMPTS_EXHAUSTED");
            } else {
                requeueAfterExpiredLease(row);
            }
            return null;
        }

        if (row.attempts() >= row.maxAttempts()) {
            markFailed(row, "ATTEMPTS_EXHAUSTED");
            return null;
        }

        String owner = UUID.randomUUID().toString();
        long dispatchGeneration = row.dispatchGeneration() + 1;
        int updated = jdbcTemplate.update("""
                        UPDATE report_notification_intent
                        SET status = 'DISPATCHING',
                            attempts = attempts + 1,
                            dispatch_generation = ?,
                            dispatch_owner = ?,
                            dispatch_lease_until = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL ? SECOND),
                            next_attempt_at = NULL,
                            updated_at = CURRENT_TIMESTAMP(3)
                        WHERE id = ?
                          AND status = 'PENDING'
                          AND attempts = ?
                          AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP(3))
                        """, dispatchGeneration, owner, Math.max(1, dispatchLeaseTtlSeconds),
                row.id(), row.attempts());
        if (updated != 1) {
            throw new IllegalStateException("DISPATCH_CLAIM_RACE");
        }
        return new ReportNotificationClaim(row.id(), row.eventKind(), row.jobId(), row.classId(),
                row.studentId(), row.recipientId(), row.generationNo(), row.terminalRevision(),
                dispatchGeneration, owner, row.attempts() + 1, row.maxAttempts());
    }

    /**
     * Writes the in-app notification and the DELIVERED transition in one transaction. A fence or
     * terminal/permission rejection performs no delivery write.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public DeliveryOutcome deliver(ReportNotificationClaim claim) {
        ReportNoticeContextSnapshot context = lockContext(claim);
        IntentRow row = lockIntent(claim.intentId());
        if (row == null || !ownedWithLiveLease(row, claim)) return DeliveryOutcome.FENCE_LOST;

        String terminalSkip = terminalSkipReason(context);
        if (terminalSkip != null) {
            markSkipped(row, "SKIPPED_SUPERSEDED", terminalSkip);
            return DeliveryOutcome.SKIPPED;
        }
        if (!context.recipientAuthorized()) {
            markSkipped(row, "SKIPPED_PERMISSION",
                    safeCode(context.authFailureCode(), "AUTH_REVOKED"));
            return DeliveryOutcome.SKIPPED;
        }
        if (!lockAndReadReportPreference(claim.recipientId())) {
            markSkipped(row, "SKIPPED_PREFERENCE", "REPORT_PREFERENCES_DISABLED");
            return DeliveryOutcome.SKIPPED;
        }

        NotificationType type = NotificationType.valueOf(claim.eventKind());
        String title = type == NotificationType.REPORT_READY ? READY_TITLE : FAILED_TITLE;
        String content = type == NotificationType.REPORT_READY ? READY_CONTENT : FAILED_CONTENT;
        Long notificationId = notificationService.createReportNotification(
                claim.recipientId(), type, claim.jobId(), title, content);

        int updated = jdbcTemplate.update("""
                        UPDATE report_notification_intent
                        SET status = 'DELIVERED',
                            notification_id = ?,
                            delivered_at = CURRENT_TIMESTAMP(3),
                            dispatch_owner = NULL,
                            dispatch_lease_until = NULL,
                            next_attempt_at = NULL,
                            last_error_code = NULL,
                            updated_at = CURRENT_TIMESTAMP(3)
                        WHERE id = ?
                          AND status = 'DISPATCHING'
                          AND dispatch_owner = ?
                          AND dispatch_generation = ?
                          AND attempts = ?
                          AND dispatch_lease_until > CURRENT_TIMESTAMP(3)
                        """, notificationId, claim.intentId(), claim.dispatchOwner(),
                claim.dispatchGeneration(), claim.attempts());
        if (updated != 1) {
            throw new IllegalStateException("DISPATCH_DELIVERY_FENCE_LOST");
        }
        return DeliveryOutcome.DELIVERED;
    }

    /** Preference off is a terminal, non-error skip; stale/permission facts still win. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public boolean skipForPreference(ReportNotificationClaim claim) {
        ReportNoticeContextSnapshot context = lockContext(claim);
        IntentRow row = lockIntent(claim.intentId());
        if (row == null || !ownedWithLiveLease(row, claim)) return false;

        String terminalSkip = terminalSkipReason(context);
        if (terminalSkip != null) {
            markSkipped(row, "SKIPPED_SUPERSEDED", terminalSkip);
            return true;
        }
        if (!context.recipientAuthorized()) {
            markSkipped(row, "SKIPPED_PERMISSION",
                    safeCode(context.authFailureCode(), "AUTH_REVOKED"));
            return true;
        }
        if (lockAndReadReportPreference(claim.recipientId())) {
            // The dispatcher's earlier read is only advisory. Reopening before this locked
            // decision must leave the intent retryable without resetting consumed budget.
            int updated = jdbcTemplate.update("""
                    UPDATE report_notification_intent
                    SET status = 'PENDING', dispatch_owner = NULL, dispatch_lease_until = NULL,
                        next_attempt_at = CURRENT_TIMESTAMP(3), last_error_code = NULL,
                        updated_at = CURRENT_TIMESTAMP(3)
                    WHERE id = ? AND status = 'DISPATCHING'
                      AND dispatch_owner = ? AND dispatch_generation = ? AND attempts = ?
                      AND dispatch_lease_until > CURRENT_TIMESTAMP(3)
                    """, claim.intentId(), claim.dispatchOwner(), claim.dispatchGeneration(), claim.attempts());
            if (updated != 1) throw new IllegalStateException("DISPATCH_PREFERENCE_REQUEUE_FENCE_LOST");
            return true;
        }
        markSkipped(row, "SKIPPED_PREFERENCE", "REPORT_PREFERENCES_DISABLED");
        return true;
    }

    private boolean lockAndReadReportPreference(Long recipientId) {
        // Preferences live on user; no independent preference table or version column exists.
        // READ_COMMITTED makes the strict read see the writer that committed before this lock.
        jdbcTemplate.queryForObject("SELECT id FROM user WHERE id = ? FOR SHARE", Long.class, recipientId);
        return notificationService.loadReportPreferencesStrict(recipientId).reports();
    }

    /**
     * Fenced failure record. Returns false when this owner no longer holds a live lease; the caller
     * must then leave the row for lease-expiry recovery.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean recordFailure(ReportNotificationClaim claim, String errorCode) {
        IntentRow row = lockIntent(claim.intentId());
        if (row == null || !ownedWithLiveLease(row, claim)) return false;

        String safeError = safeCode(errorCode, "DISPATCH_FAILED");
        int updated;
        if (row.attempts() >= row.maxAttempts()) {
            updated = jdbcTemplate.update("""
                            UPDATE report_notification_intent
                            SET status = 'FAILED',
                                dispatch_owner = NULL,
                                dispatch_lease_until = NULL,
                                next_attempt_at = NULL,
                                last_error_code = ?,
                                updated_at = CURRENT_TIMESTAMP(3)
                            WHERE id = ?
                              AND status = 'DISPATCHING'
                              AND dispatch_owner = ?
                              AND dispatch_generation = ?
                              AND attempts = ?
                              AND dispatch_lease_until > CURRENT_TIMESTAMP(3)
                            """, safeError, claim.intentId(), claim.dispatchOwner(),
                    claim.dispatchGeneration(), claim.attempts());
        } else {
            updated = jdbcTemplate.update("""
                            UPDATE report_notification_intent
                            SET status = 'PENDING',
                                next_attempt_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL ? SECOND),
                                dispatch_owner = NULL,
                                dispatch_lease_until = NULL,
                                last_error_code = ?,
                                updated_at = CURRENT_TIMESTAMP(3)
                            WHERE id = ?
                              AND status = 'DISPATCHING'
                              AND dispatch_owner = ?
                              AND dispatch_generation = ?
                              AND attempts = ?
                              AND dispatch_lease_until > CURRENT_TIMESTAMP(3)
                            """, backoffSeconds(row.attempts()), safeError, claim.intentId(),
                    claim.dispatchOwner(), claim.dispatchGeneration(), claim.attempts());
        }
        if (updated != 1) {
            throw new IllegalStateException("DISPATCH_FAILURE_FENCE_LOST");
        }
        return true;
    }

    private ReportNoticeContextSnapshot lockContext(IntentRow identity) {
        return noticeContextQuery.lockCurrentContext(identity.jobId(), identity.eventKind(),
                identity.generationNo(), identity.terminalRevision(), identity.recipientId());
    }

    private ReportNoticeContextSnapshot lockContext(ReportNotificationClaim claim) {
        return noticeContextQuery.lockCurrentContext(claim.jobId(), claim.eventKind(),
                claim.generationNo(), claim.terminalRevision(), claim.recipientId());
    }

    private static String terminalSkipReason(ReportNoticeContextSnapshot context) {
        if (context == null || "NOT_FOUND".equals(context.jobStatus())) {
            return "REPORT_JOB_NOT_FOUND";
        }
        if (!context.withinRetention()) return "RETENTION_EXPIRED";
        if (!context.currentTerminal()) return "STALE_TERMINAL";
        return null;
    }

    private void markSkipped(IntentRow row, String status, String errorCode) {
        int updated = jdbcTemplate.update("""
                        UPDATE report_notification_intent
                        SET status = ?,
                            dispatch_owner = NULL,
                            dispatch_lease_until = NULL,
                            next_attempt_at = NULL,
                            last_error_code = ?,
                            updated_at = CURRENT_TIMESTAMP(3)
                        WHERE id = ?
                          AND status IN ('PENDING', 'DISPATCHING')
                          AND notification_id IS NULL
                        """, status, safeCode(errorCode, "DISPATCH_SKIPPED"), row.id());
        if (updated != 1) {
            throw new IllegalStateException("DISPATCH_SKIP_RACE");
        }
    }

    private void markFailed(IntentRow row, String errorCode) {
        int updated = jdbcTemplate.update("""
                        UPDATE report_notification_intent
                        SET status = 'FAILED',
                            dispatch_owner = NULL,
                            dispatch_lease_until = NULL,
                            next_attempt_at = NULL,
                            last_error_code = ?,
                            updated_at = CURRENT_TIMESTAMP(3)
                        WHERE id = ?
                          AND status IN ('PENDING', 'DISPATCHING')
                          AND notification_id IS NULL
                        """, safeCode(errorCode, "DISPATCH_FAILED"), row.id());
        if (updated != 1) {
            throw new IllegalStateException("DISPATCH_FAILURE_RACE");
        }
    }

    private void requeueAfterExpiredLease(IntentRow row) {
        int updated = jdbcTemplate.update("""
                        UPDATE report_notification_intent
                        SET status = 'PENDING',
                            dispatch_owner = NULL,
                            dispatch_lease_until = NULL,
                            next_attempt_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL ? SECOND),
                            last_error_code = 'DISPATCH_LEASE_EXPIRED',
                            updated_at = CURRENT_TIMESTAMP(3)
                        WHERE id = ?
                          AND status = 'DISPATCHING'
                          AND attempts = ?
                        """, backoffSeconds(row.attempts()), row.id(), row.attempts());
        if (updated != 1) {
            throw new IllegalStateException("DISPATCH_LEASE_RECOVERY_RACE");
        }
    }

    private IntentRow readIdentity(Long intentId) {
        List<IntentRow> rows = jdbcTemplate.query("""
                        SELECT id, event_key, event_kind, job_id, class_id, student_id, recipient_id,
                               generation_no, terminal_revision, source_error_code, status, attempts,
                               max_attempts, dispatch_generation, dispatch_owner, dispatch_lease_until,
                               next_attempt_at, last_error_code, notification_id,
                               0 AS due, 0 AS lease_live
                        FROM report_notification_intent
                        WHERE id = ?
                        """, this::mapRow, intentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private IntentRow lockIntent(Long intentId) {
        List<IntentRow> rows = jdbcTemplate.query("""
                        SELECT id, event_key, event_kind, job_id, class_id, student_id, recipient_id,
                               generation_no, terminal_revision, source_error_code, status, attempts,
                               max_attempts, dispatch_generation, dispatch_owner, dispatch_lease_until,
                               next_attempt_at, last_error_code, notification_id,
                               (status = 'PENDING'
                                AND (next_attempt_at IS NULL
                                     OR next_attempt_at <= CURRENT_TIMESTAMP(3)))
                               OR (status = 'DISPATCHING'
                                   AND dispatch_lease_until <= CURRENT_TIMESTAMP(3)) AS due,
                               (dispatch_lease_until IS NOT NULL
                                AND dispatch_lease_until > CURRENT_TIMESTAMP(3)) AS lease_live
                        FROM report_notification_intent
                        WHERE id = ?
                        FOR UPDATE
                        """, this::mapRow, intentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private IntentRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new IntentRow(
                rs.getLong("id"),
                rs.getString("event_key"),
                rs.getString("event_kind"),
                rs.getLong("job_id"),
                rs.getLong("class_id"),
                rs.getLong("student_id"),
                nullableLong(rs, "recipient_id"),
                rs.getLong("generation_no"),
                rs.getLong("terminal_revision"),
                rs.getString("source_error_code"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getLong("dispatch_generation"),
                rs.getString("dispatch_owner"),
                rs.getObject("dispatch_lease_until", LocalDateTime.class),
                rs.getObject("next_attempt_at", LocalDateTime.class),
                rs.getString("last_error_code"),
                nullableLong(rs, "notification_id"),
                rs.getBoolean("due"),
                rs.getBoolean("lease_live"));
    }

    private static boolean ownedWithLiveLease(IntentRow row, ReportNotificationClaim claim) {
        return "DISPATCHING".equals(row.status())
                && Objects.equals(claim.intentId(), row.id())
                && Objects.equals(claim.dispatchOwner(), row.dispatchOwner())
                && claim.dispatchGeneration() == row.dispatchGeneration()
                && claim.attempts() == row.attempts()
                && row.notificationId() == null
                && row.leaseLive();
    }

    private static int backoffSeconds(int attempts) {
        int index = (Math.max(1, attempts) - 1) % 5;
        return index >= BACKOFF_SECONDS.length ? BACKOFF_SECONDS[BACKOFF_SECONDS.length - 1]
                : BACKOFF_SECONDS[index];
    }

    private static String safeCode(String value, String fallback) {
        return value != null && SAFE_ERROR_CODE.matcher(value).matches() ? value : fallback;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    /** Delivery outcomes that are not infrastructure failures. */
    public enum DeliveryOutcome {
        DELIVERED,
        SKIPPED,
        FENCE_LOST
    }

    private record IntentRow(
            Long id,
            String eventKey,
            String eventKind,
            Long jobId,
            Long classId,
            Long studentId,
            Long recipientId,
            Long generationNo,
            Long terminalRevision,
            String sourceErrorCode,
            String status,
            int attempts,
            int maxAttempts,
            long dispatchGeneration,
            String dispatchOwner,
            LocalDateTime dispatchLeaseUntil,
            LocalDateTime nextAttemptAt,
            String lastErrorCode,
            Long notificationId,
            boolean due,
            boolean leaseLive
    ) {
        boolean sameIdentity(IntentRow other) {
            return Objects.equals(eventKey, other.eventKey)
                    && Objects.equals(eventKind, other.eventKind)
                    && Objects.equals(jobId, other.jobId)
                    && Objects.equals(classId, other.classId)
                    && Objects.equals(studentId, other.studentId)
                    && Objects.equals(recipientId, other.recipientId)
                    && Objects.equals(generationNo, other.generationNo)
                    && Objects.equals(terminalRevision, other.terminalRevision)
                    && Objects.equals(sourceErrorCode, other.sourceErrorCode);
        }
    }
}
