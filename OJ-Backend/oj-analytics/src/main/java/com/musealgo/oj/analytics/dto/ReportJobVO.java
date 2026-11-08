package com.musealgo.oj.analytics.dto;

import com.musealgo.oj.analytics.entity.ReportJob;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** T9-2 报告任务视图；列表不返回 html，详情才返回。 */
public record ReportJobVO(
        Long id,
        Long studentId,
        String studentName,
        Long classId,
        LocalDate periodStart,
        LocalDate periodEnd,
        String status,
        String errorMessage,
        int attempts,
        Long requesterId,
        LocalDateTime createTime,
        LocalDateTime finishedAt,
        String html,
        List<Delivery> deliveries,
        Long activeRequesterId,
        Long generationNo,
        Long terminalRevision,
        Long snapshotId,
        String lastErrorCode,
        LocalDateTime nextAttemptAt,
        boolean canRetry,
        GenerationFailure generationFailure
) {
    public record GenerationFailure(String code, String safeMessage,
                                    boolean canRetry, Long terminalRevision) {
    }

    public record Delivery(Long id, String channel, Long recipientId, String status,
                           int attempts, String errorMessage, LocalDateTime deliveredAt) {
    }

    public static ReportJobVO of(ReportJob job, String html, List<Delivery> deliveries) {
        boolean canRetry = com.musealgo.oj.analytics.application.ReportFailurePolicy.canRetry(job);
        String failureCode = com.musealgo.oj.analytics.application.ReportFailurePolicy.effectiveCode(job);
        GenerationFailure failure = "FAILED".equals(job.getStatus())
                ? new GenerationFailure(failureCode,
                        com.musealgo.oj.analytics.application.ReportFailurePolicy.safeMessage(failureCode),
                        canRetry, job.getTerminalRevision())
                : null;
        String safeErrorMessage = "FAILED".equals(job.getStatus())
                ? com.musealgo.oj.analytics.application.ReportFailurePolicy.safeMessage(failureCode)
                : job.getErrorMessage();
        return new ReportJobVO(job.getId(), job.getStudentId(), job.getStudentName(), job.getClassId(),
                job.getPeriodStart(), job.getPeriodEnd(), job.getStatus(), safeErrorMessage,
                job.getAttempts() == null ? 0 : job.getAttempts(), job.getRequesterId(),
                job.getCreateTime(), job.getFinishedAt(), html, deliveries,
                job.getActiveRequesterId(), job.getGenerationNo(), job.getTerminalRevision(),
                job.getSnapshotId(), job.getLastErrorCode(), job.getNextAttemptAt(), canRetry, failure);
    }
}
