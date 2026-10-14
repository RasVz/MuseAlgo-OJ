package top.everblue.oj.analytics.dto;

import java.time.LocalDateTime;

/**
 * 查重报告只读视图（HTTP 层出参）。
 */
public record PlagiarismReportVO(
        Long id,
        Long classId,
        Long contestId,
        Long problemId,
        Long submissionAId,
        Long submissionBId,
        Long userAId,
        Long userBId,
        String userAName,
        String userBName,
        Double similarity,
        String method,
        String status,
        LocalDateTime createdAt) {
}
