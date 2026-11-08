package com.musealgo.oj.community.notification;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.musealgo.oj.api.report.ReportIntentCommand;
import com.musealgo.oj.api.report.ReportNotificationIntentStatus;
import com.musealgo.oj.api.report.ReportNotificationPort;
import com.musealgo.oj.api.report.ReportNotificationStatusQuery;
import com.musealgo.oj.api.report.ReportNotificationStatusSnapshot;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Notice-owned persistence for report intents and recipient-facing notice facts. */
@Repository
public class ReportNotificationPersistenceAdapter
        implements ReportNotificationPort, ReportNotificationStatusQuery {

    private static final Pattern SAFE_SOURCE_ERROR = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private final JdbcTemplate jdbcTemplate;

    public ReportNotificationPersistenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void stageReadyIntent(ReportIntentCommand command) {
        if (command == null || command.errorCode() != null) {
            throw new IllegalArgumentException("READY intent must not have a source error code");
        }
        stage("REPORT_READY", command, null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void stageFailedIntent(ReportIntentCommand command) {
        if (command == null) throw new IllegalArgumentException("FAILED intent command is required");
        String sourceErrorCode = requireSafeSourceError(command.errorCode());
        stage("REPORT_FAILED", command, sourceErrorCode);
    }

    private void stage(String eventKind, ReportIntentCommand command, String sourceErrorCode) {
        validateIdentity(command);
        if (command.recipientId() == null && !"REPORT_FAILED".equals(eventKind)) {
            throw new IllegalArgumentException("Only a failed intent may be unassigned");
        }

        String recipientKey = command.recipientId() == null ? "UNASSIGNED" : command.recipientId().toString();
        String eventKey = eventKind + ":" + command.jobId() + ":" + command.generationNo() + ":"
                + command.terminalRevision() + ":" + recipientKey;
        String initialStatus = command.recipientId() == null ? "SKIPPED_PERMISSION" : "PENDING";
        try {
            jdbcTemplate.update("""
                            INSERT INTO report_notification_intent
                                (event_key, event_kind, job_id, class_id, student_id, recipient_id,
                                 generation_no, terminal_revision, source_error_code, status, next_attempt_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                    CASE WHEN ? = 'PENDING' THEN CURRENT_TIMESTAMP(3) ELSE NULL END)
                            """,
                    eventKey, eventKind, command.jobId(), command.classId(), command.studentId(),
                    command.recipientId(), command.generationNo(), command.terminalRevision(),
                    sourceErrorCode, initialStatus, initialStatus);
        } catch (DuplicateKeyException duplicate) {
            verifyDuplicate(eventKey, eventKind, command, sourceErrorCode, duplicate);
        }
    }

    private void verifyDuplicate(String eventKey, String eventKind, ReportIntentCommand command,
                                 String sourceErrorCode, DuplicateKeyException duplicate) {
        List<ImmutableIntent> rows = jdbcTemplate.query("""
                        SELECT event_kind, job_id, class_id, student_id, recipient_id,
                               generation_no, terminal_revision, source_error_code
                        FROM report_notification_intent
                        WHERE event_key = ?
                        FOR UPDATE
                        """,
                (rs, rowNum) -> new ImmutableIntent(
                        rs.getString("event_kind"), rs.getLong("job_id"), rs.getLong("class_id"),
                        rs.getLong("student_id"), nullableLong(rs, "recipient_id"),
                        rs.getLong("generation_no"), rs.getLong("terminal_revision"),
                        rs.getString("source_error_code")), eventKey);
        if (rows.size() != 1) {
            throw new IllegalStateException("Duplicate intent key could not be resolved", duplicate);
        }
        ImmutableIntent existing = rows.get(0);
        boolean same = eventKind.equals(existing.eventKind())
                && command.jobId().equals(existing.jobId())
                && command.classId().equals(existing.classId())
                && command.studentId().equals(existing.studentId())
                && Objects.equals(command.recipientId(), existing.recipientId())
                && command.generationNo().equals(existing.generationNo())
                && command.terminalRevision().equals(existing.terminalRevision())
                && Objects.equals(sourceErrorCode, existing.sourceErrorCode());
        if (!same) throw new IllegalStateException("Conflicting immutable report notification intent", duplicate);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void supersedePendingFailureIntents(Long jobId, Long terminalRevision) {
        requirePositive(jobId, "jobId");
        requirePositive(terminalRevision, "terminalRevision");
        jdbcTemplate.update("""
                        UPDATE report_notification_intent
                        SET status = 'SKIPPED_SUPERSEDED', dispatch_owner = NULL,
                            dispatch_lease_until = NULL, next_attempt_at = NULL,
                            updated_at = CURRENT_TIMESTAMP(3)
                        WHERE job_id = ? AND event_kind = 'REPORT_FAILED' AND terminal_revision = ?
                          AND notification_id IS NULL
                          AND status IN ('PENDING', 'DISPATCHING', 'FAILED')
                        """, jobId, terminalRevision);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Set<Long> deleteTerminalIntentsForExpiredJobs(List<Long> candidateJobIds) {
        if (candidateJobIds == null || candidateJobIds.isEmpty()) return Set.of();
        TreeSet<Long> sortedJobIds = new TreeSet<>();
        for (Long jobId : candidateJobIds) {
            requirePositive(jobId, "candidateJobId");
            sortedJobIds.add(jobId);
        }

        Set<Long> deletedJobs = new TreeSet<>();
        for (Long jobId : sortedJobIds) {
            List<CleanupIntent> intents = jdbcTemplate.query("""
                            SELECT notification_id
                            FROM report_notification_intent
                            WHERE job_id = ?
                            ORDER BY id
                            FOR UPDATE
                            """,
                    (rs, rowNum) -> new CleanupIntent(
                            nullableLong(rs, "notification_id")), jobId);

            // The report owner already locked an expired terminal job. Notification polling or
            // a future retry must not extend its retention indefinitely. Keep job -> intent order
            // and invalidate live claims before deleting, all within the caller's transaction.
            jdbcTemplate.update("""
                    UPDATE report_notification_intent
                    SET status = 'SKIPPED_SUPERSEDED', last_error_code = 'RETENTION_EXPIRED',
                        dispatch_owner = NULL, dispatch_lease_until = NULL, next_attempt_at = NULL,
                        updated_at = CURRENT_TIMESTAMP(3)
                    WHERE job_id = ? AND status IN ('PENDING', 'DISPATCHING')
                      AND notification_id IS NULL
                    """, jobId);

            List<Long> notificationIds = new ArrayList<>();
            for (CleanupIntent intent : intents) {
                if (intent.notificationId() != null) notificationIds.add(intent.notificationId());
            }
            // The FK points from the intent to user_notification, so remove intent rows first.
            jdbcTemplate.update("DELETE FROM report_notification_intent WHERE job_id = ?", jobId);
            for (Long notificationId : notificationIds) {
                jdbcTemplate.update("DELETE FROM user_notification WHERE id = ?", notificationId);
            }
            // Remove historical listener-created rows exactly scoped to this report job.
            jdbcTemplate.update("""
                    DELETE FROM user_notification
                    WHERE target_type = 'REPORT' AND target_id = ?
                      AND type IN ('REPORT_READY', 'REPORT_FAILED')
                    """, jobId);
            deletedJobs.add(jobId);
        }
        return Collections.unmodifiableSet(deletedJobs);
    }

    @Override
    @Transactional(readOnly = true)
    public ReportNotificationStatusSnapshot findForJob(
            Long jobId, Long recipientId, Long currentTerminalRevision) {
        requirePositive(jobId, "jobId");
        requirePositive(recipientId, "recipientId");
        if (currentTerminalRevision == null || currentTerminalRevision < 0) {
            throw new IllegalArgumentException("currentTerminalRevision must be nonnegative");
        }
        List<ReportNotificationIntentStatus> intents = jdbcTemplate.query("""
                        SELECT i.event_kind, i.generation_no, i.terminal_revision, i.status,
                               i.attempts, i.max_attempts, i.next_attempt_at, i.last_error_code,
                               n.id AS visible_notification_id, COALESCE(n.is_read, 0) AS notification_is_read,
                               n.read_time AS notification_read_time
                        FROM report_notification_intent i
                        LEFT JOIN user_notification n
                          ON n.id = i.notification_id AND n.user_id = i.recipient_id
                        WHERE i.job_id = ? AND i.recipient_id = ? AND i.terminal_revision = ?
                        ORDER BY CASE i.event_kind WHEN 'REPORT_READY' THEN 0 ELSE 1 END, i.id
                        """,
                (rs, rowNum) -> new ReportNotificationIntentStatus(
                        rs.getString("event_kind"), rs.getLong("generation_no"),
                        rs.getLong("terminal_revision"), rs.getString("status"),
                        rs.getInt("attempts"), rs.getInt("max_attempts"),
                        instant(rs, "next_attempt_at"), rs.getString("last_error_code"),
                        nullableLong(rs, "visible_notification_id"),
                        rs.getBoolean("notification_is_read"), instant(rs, "notification_read_time")),
                jobId, recipientId, currentTerminalRevision);
        return new ReportNotificationStatusSnapshot(jobId, currentTerminalRevision, intents);
    }

    private static String requireSafeSourceError(String value) {
        if (value == null || !SAFE_SOURCE_ERROR.matcher(value).matches()) {
            throw new IllegalArgumentException("FAILED intent requires a safe ASCII source error code");
        }
        return value;
    }

    private static void validateIdentity(ReportIntentCommand command) {
        requirePositive(command.jobId(), "jobId");
        requirePositive(command.classId(), "classId");
        requirePositive(command.studentId(), "studentId");
        if (command.recipientId() != null) requirePositive(command.recipientId(), "recipientId");
        requireNonnegative(command.generationNo(), "generationNo");
        requirePositive(command.terminalRevision(), "terminalRevision");
    }

    private static void requireNonnegative(Long value, String field) {
        if (value == null || value < 0) throw new IllegalArgumentException(field + " must be nonnegative");
    }

    private static void requirePositive(Long value, String field) {
        if (value == null || value <= 0) throw new IllegalArgumentException(field + " must be positive");
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return ReportNotificationDateTimes.toInstant(value);
    }

    private record ImmutableIntent(String eventKind, Long jobId, Long classId, Long studentId,
                                   Long recipientId, Long generationNo, Long terminalRevision,
                                   String sourceErrorCode) { }

    private record CleanupIntent(Long notificationId) { }
}
