package com.musealgo.oj.api.report;

import java.time.Instant;

/**
 * 单条报告通知意图的状态与关联站内通知已读事实；不包含报告正文或通知内容。
 * {@code eventKind} 为 REPORT_READY 或 REPORT_FAILED；未关联通知时
 * {@code notificationId}、{@code readAt} 为空且 {@code isRead} 为 false。
 */
public record ReportNotificationIntentStatus(
        String eventKind,
        Long generationNo,
        Long terminalRevision,
        String status,
        int attempts,
        int maxAttempts,
        Instant nextAttemptAt,
        String lastErrorCode,
        Long notificationId,
        boolean isRead,
        Instant readAt
) {
}
