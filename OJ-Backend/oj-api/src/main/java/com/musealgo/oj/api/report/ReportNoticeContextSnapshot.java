package com.musealgo.oj.api.report;

/** Minimal fresh report facts required to decide whether a notification intent is still valid. */
public record ReportNoticeContextSnapshot(
        Long jobId,
        Long classId,
        Long studentId,
        String jobStatus,
        Long currentGenerationNo,
        Long currentTerminalRevision,
        Long activeRequesterId,
        Long recipientId,
        boolean currentTerminal,
        boolean recipientAuthorized,
        boolean withinRetention,
        String authFailureCode
) {
}
