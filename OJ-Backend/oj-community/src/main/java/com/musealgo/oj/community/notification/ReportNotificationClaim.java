package com.musealgo.oj.community.notification;

/**
 * A committed dispatch claim for one report notification intent.
 * <p>
 * The claim records the immutable intent identity, the persisted attempt number that was
 * consumed, and the fencing token/owner/lease that every later write must match. It is valid
 * only while the database row still says {@code DISPATCHING} for the same owner, generation,
 * attempt number, and a live lease.
 * </p>
 */
public record ReportNotificationClaim(
        Long intentId,
        String eventKind,
        Long jobId,
        Long classId,
        Long studentId,
        Long recipientId,
        Long generationNo,
        Long terminalRevision,
        long dispatchGeneration,
        String dispatchOwner,
        int attempts,
        int maxAttempts
) {
}
