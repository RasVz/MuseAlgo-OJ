package com.musealgo.oj.community.notification;

import java.time.Instant;

/**
 * Safe administrator projection of one report notification intent. It deliberately excludes
 * report HTML, student names, class/student identifiers, and notification content.
 * {@code sourceErrorCode} is the immutable report generation failure (null for READY);
 * {@code lastErrorCode} is the mutable notice dispatch/skip diagnostic.
 */
public record ReportNotificationAdminIntent(
        Long intentId,
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
        Instant nextAttemptAt,
        String lastErrorCode,
        Long notificationId,
        Long redriveAdminId,
        Instant redrivenAt,
        String redriveReason,
        Instant createdAt,
        Instant updatedAt
) {
}
