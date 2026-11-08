package com.musealgo.oj.community.notification;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.community.error.CommunityErrorCode;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.musealgo.oj.api.report.ReportNoticeContextQuery;
import com.musealgo.oj.api.report.ReportNoticeContextSnapshot;
import com.musealgo.oj.community.service.NotificationService;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Administrator queue and the single allowed redrive. The outbox columns written here are the
 * authoritative audit; an asynchronous AuditLog entry is additional evidence, not a substitute.
 */
@Service
@RequiredArgsConstructor
public class ReportNotificationAdminService {

    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "PENDING", "DISPATCHING", "DELIVERED", "SKIPPED_PREFERENCE",
            "SKIPPED_PERMISSION", "SKIPPED_SUPERSEDED", "FAILED");
    private static final Set<String> ALLOWED_EVENT_KINDS = Set.of("REPORT_READY", "REPORT_FAILED");
    private static final int MIN_REASON_CODE_POINTS = 10;
    private static final int MAX_REASON_CODE_POINTS = 200;

    private final JdbcTemplate jdbcTemplate;
    private final ReportNoticeContextQuery noticeContextQuery;
    private final NotificationService notificationService;

    @Transactional(readOnly = true)
    public ReportNotificationAdminPage list(String status, String eventKind, Long jobId,
                                            int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        String normalizedStatus = normalizeStatus(status);
        String normalizedEventKind = normalizeEventKind(eventKind);
        if (jobId != null && jobId <= 0) {
            throw new BusinessException(CommunityErrorCode.COMMUNITY_INPUT_INVALID, "任务 ID 必须为正数");
        }

        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (normalizedStatus != null) {
            where.append(" AND status = ?");
            args.add(normalizedStatus);
        }
        if (normalizedEventKind != null) {
            where.append(" AND event_kind = ?");
            args.add(normalizedEventKind);
        }
        if (jobId != null) {
            where.append(" AND job_id = ?");
            args.add(jobId);
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM report_notification_intent" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) (safePage - 1) * safeSize);
        // 状态已固定时把索引前缀写入排序；全状态查询仍保持全局更新时间顺序。
        String order = normalizedStatus == null
                ? " ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?"
                : " ORDER BY status DESC, updated_at DESC, id DESC LIMIT ? OFFSET ?";
        List<ReportNotificationAdminIntent> items = jdbcTemplate.query("""
                        SELECT id, event_kind, job_id, recipient_id, generation_no, terminal_revision,
                               source_error_code, status, attempts, max_attempts, redrive_round,
                               next_attempt_at, last_error_code, notification_id, redrive_admin_id,
                               redriven_at, redrive_reason, created_at, updated_at
                        FROM report_notification_intent
                        """ + where + order, this::mapAdminIntent, pageArgs.toArray());
        return new ReportNotificationAdminPage(items, total == null ? 0L : total, safePage, safeSize);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public RedriveOutcome redrive(Long intentId, String reason, Long adminId) {
        if (intentId == null || intentId <= 0) {
            return RedriveOutcome.rejected(404, "INTENT_NOT_FOUND", "通知意图不存在");
        }
        String cleanReason = reason == null ? null : reason.trim();
        if (!validReason(cleanReason)) {
            return RedriveOutcome.rejected(400, "REASON_INVALID", "重投原因需为 10-200 字");
        }
        if (adminId == null) {
            return RedriveOutcome.rejected(401, "UNAUTHENTICATED", "请先登录");
        }

        AdminRow identity = readIdentity(intentId);
        if (identity == null) {
            return RedriveOutcome.rejected(404, "INTENT_NOT_FOUND", "通知意图不存在");
        }
        if (!redriveEligible(identity)) {
            return RedriveOutcome.rejected(409, eligibilityCode(identity),
                    "仅当前修订、未过期的投递失败通知可重投一次");
        }

        ReportNoticeContextSnapshot context = noticeContextQuery.lockCurrentContext(
                identity.jobId(), identity.eventKind(), identity.generationNo(),
                identity.terminalRevision(), identity.recipientId());
        AdminRow row = lockIntent(intentId);
        if (row == null) {
            return RedriveOutcome.rejected(404, "INTENT_NOT_FOUND", "通知意图不存在");
        }
        if (!redriveEligible(row)) {
            return RedriveOutcome.rejected(409, eligibilityCode(row),
                    "仅当前修订、未过期的投递失败通知可重投一次");
        }
        if (context == null || !context.withinRetention()) {
            return RedriveOutcome.rejected(409, "REDRIVE_EXPIRED", "通知意图已超过留存期");
        }
        if (!context.currentTerminal()) {
            return RedriveOutcome.rejected(409, "REDRIVE_SUPERSEDED",
                    "该通知已不是当前报告终态修订");
        }
        if (!context.recipientAuthorized()) {
            return RedriveOutcome.rejected(409, "REDRIVE_PERMISSION_REVOKED",
                    "收件人已无权接收该报告提醒");
        }
        final boolean preferencesEnabled;
        try {
            preferencesEnabled = notificationService
                    .loadReportPreferencesStrict(row.recipientId()).reports();
        } catch (RuntimeException error) {
            return RedriveOutcome.rejected(409, "REDRIVE_PREFERENCES_UNAVAILABLE",
                    "通知偏好当前不可读取，暂不能重投");
        }
        if (!preferencesEnabled) {
            return RedriveOutcome.rejected(409, "REDRIVE_PREFERENCES_DISABLED",
                    "收件人已关闭报告提醒");
        }

        int updated = jdbcTemplate.update("""
                        UPDATE report_notification_intent
                        SET status = 'PENDING',
                            max_attempts = 10,
                            redrive_round = 1,
                            next_attempt_at = CURRENT_TIMESTAMP(3),
                            dispatch_owner = NULL,
                            dispatch_lease_until = NULL,
                            redrive_admin_id = ?,
                            redriven_at = CURRENT_TIMESTAMP(3),
                            redrive_reason = ?,
                            updated_at = CURRENT_TIMESTAMP(3)
                        WHERE id = ?
                          AND status = 'FAILED'
                          AND notification_id IS NULL
                          AND redrive_round = 0
                          AND max_attempts = 5
                          AND attempts = 5
                        """, adminId, cleanReason, intentId);
        if (updated != 1) {
            return RedriveOutcome.rejected(409, "REDRIVE_RACE", "通知状态已变化，请刷新后重试");
        }
        // Keep the prior delivery error until the next attempt. The controller's audit records
        // this code from the returned immutable view, so a later success cannot erase its trace.
        ReportNotificationAdminIntent refreshed = findById(intentId);
        if (refreshed == null) {
            return RedriveOutcome.rejected(409, "REDRIVE_RACE", "通知状态已变化，请刷新后重试");
        }
        return RedriveOutcome.success(refreshed);
    }

    private static boolean redriveEligible(AdminRow row) {
        return "FAILED".equals(row.status())
                && row.notificationId() == null
                && row.redriveRound() == 0
                && row.maxAttempts() == 5
                && row.attempts() == 5;
    }

    private static String eligibilityCode(AdminRow row) {
        if (row.redriveRound() > 0) return "REDRIVE_ALREADY_USED";
        if (!"FAILED".equals(row.status())) return "REDRIVE_NOT_FAILED";
        return "REDRIVE_NOT_ALLOWED";
    }

    private static boolean validReason(String reason) {
        if (reason == null) return false;
        int points = reason.codePointCount(0, reason.length());
        return points >= MIN_REASON_CODE_POINTS && points <= MAX_REASON_CODE_POINTS;
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return null;
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_STATUSES.contains(normalized)) {
            throw new BusinessException(CommunityErrorCode.COMMUNITY_INPUT_INVALID, "不支持的通知状态");
        }
        return normalized;
    }

    private static String normalizeEventKind(String eventKind) {
        if (eventKind == null || eventKind.isBlank()) return null;
        String normalized = eventKind.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_EVENT_KINDS.contains(normalized)) {
            throw new BusinessException(CommunityErrorCode.COMMUNITY_INPUT_INVALID, "不支持的报告通知事件类型");
        }
        return normalized;
    }

    private AdminRow readIdentity(Long intentId) {
        List<AdminRow> rows = jdbcTemplate.query("""
                        SELECT id, event_kind, job_id, recipient_id, generation_no, terminal_revision,
                               source_error_code, status, attempts, max_attempts, redrive_round,
                               next_attempt_at, last_error_code, notification_id, redrive_admin_id,
                               redriven_at, redrive_reason, created_at, updated_at
                        FROM report_notification_intent
                        WHERE id = ?
                        """, this::mapAdminRow, intentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private AdminRow lockIntent(Long intentId) {
        List<AdminRow> rows = jdbcTemplate.query("""
                        SELECT id, event_kind, job_id, recipient_id, generation_no, terminal_revision,
                               source_error_code, status, attempts, max_attempts, redrive_round,
                               next_attempt_at, last_error_code, notification_id, redrive_admin_id,
                               redriven_at, redrive_reason, created_at, updated_at
                        FROM report_notification_intent
                        WHERE id = ?
                        FOR UPDATE
                        """, this::mapAdminRow, intentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ReportNotificationAdminIntent findById(Long intentId) {
        AdminRow row = readIdentity(intentId);
        return row == null ? null : row.toView();
    }

    private ReportNotificationAdminIntent mapAdminIntent(ResultSet rs, int rowNum) throws SQLException {
        return mapAdminRow(rs, rowNum).toView();
    }

    private AdminRow mapAdminRow(ResultSet rs, int rowNum) throws SQLException {
        return new AdminRow(
                rs.getLong("id"),
                rs.getString("event_kind"),
                rs.getLong("job_id"),
                nullableLong(rs, "recipient_id"),
                rs.getLong("generation_no"),
                rs.getLong("terminal_revision"),
                rs.getString("source_error_code"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getInt("redrive_round"),
                rs.getObject("next_attempt_at", LocalDateTime.class),
                rs.getString("last_error_code"),
                nullableLong(rs, "notification_id"),
                nullableLong(rs, "redrive_admin_id"),
                rs.getObject("redriven_at", LocalDateTime.class),
                rs.getString("redrive_reason"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    private record AdminRow(
            Long id,
            String eventKind,
            Long jobId,
            Long recipientId,
            Long generationNo,
            Long terminalRevision,
            String sourceErrorCode,
            String status,
            int attempts,
            int maxAttempts,
            int redriveRound,
            LocalDateTime nextAttemptAt,
            String lastErrorCode,
            Long notificationId,
            Long redriveAdminId,
            LocalDateTime redrivenAt,
            String redriveReason,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        ReportNotificationAdminIntent toView() {
            return new ReportNotificationAdminIntent(id, eventKind, jobId, recipientId, generationNo,
                    terminalRevision, sourceErrorCode, status, attempts, maxAttempts, redriveRound,
                    ReportNotificationDateTimes.toInstant(nextAttemptAt), lastErrorCode,
                    notificationId, redriveAdminId,
                    ReportNotificationDateTimes.toInstant(redrivenAt), redriveReason,
                    ReportNotificationDateTimes.toInstant(createdAt),
                    ReportNotificationDateTimes.toInstant(updatedAt));
        }
    }

    /** Result of the one-shot redrive operation, including a stable internal rejection code. */
    public record RedriveOutcome(
            boolean success,
            int resultCode,
            String errorCode,
            String message,
            ReportNotificationAdminIntent intent
    ) {
        static RedriveOutcome success(ReportNotificationAdminIntent intent) {
            return new RedriveOutcome(true, 200, null, "ok", intent);
        }

        static RedriveOutcome rejected(int resultCode, String errorCode, String message) {
            return new RedriveOutcome(false, resultCode, errorCode, message, null);
        }
    }
}
