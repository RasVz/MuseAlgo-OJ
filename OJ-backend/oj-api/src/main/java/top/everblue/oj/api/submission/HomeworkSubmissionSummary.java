package top.everblue.oj.api.submission;

import java.time.LocalDateTime;

/** 作业提交摘要视图 — 教师端作业提交列表的只读投射。 */
public record HomeworkSubmissionSummary(
        Long id,
        Long studentId,
        String studentName,
        Long problemId,
        String problemTitle,
        String language,
        Byte status,
        String score,
        Integer runTime,
        Integer runMemory,
        String errorMessage,
        LocalDateTime createTime
) {
}
