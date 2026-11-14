package com.musealgo.oj.ai.submission;

import java.time.LocalDateTime;

/** 提交详情折叠卡读取的诊断摘要；正文只在本人查询时返回。 */
public record SubmissionDiagnosisView(
        Long submissionId,
        String status,
        String content,
        String errorCode,
        String message,
        String model,
        String serviceName,
        Long latencyMs,
        LocalDateTime startTime,
        LocalDateTime finishTime,
        LocalDateTime createTime) {

    public static SubmissionDiagnosisView of(SubmissionDiagnosis row) {
        return new SubmissionDiagnosisView(row.getSubmissionId(), row.getStatus(), row.getContent(),
                row.getErrorCode(), row.getMessage(), row.getModel(), row.getServiceName(),
                row.getLatencyMs(), row.getStartTime(), row.getFinishTime(), row.getCreateTime());
    }
}
