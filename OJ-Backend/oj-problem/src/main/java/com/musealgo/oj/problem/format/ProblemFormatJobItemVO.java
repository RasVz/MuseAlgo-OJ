package com.musealgo.oj.problem.format;

import java.util.List;

/**
 * 任务内单题摘要。
 *
 * @param status        PENDING / RUNNING / APPLIED / REVIEW / FAILED / SKIPPED
 * @param hasProposal   是否已生成可审阅的提案（有提案才能打开工作台）
 * @param issueErrors   阻断级问题数
 * @param issueWarnings 提醒级问题数
 * @param appliedFields 已写入的字段
 */
public record ProblemFormatJobItemVO(
        Long problemId,
        String title,
        String status,
        String message,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String errorCode,
        Integer attempts,
        int issueErrors,
        int issueWarnings,
        boolean hasProposal,
        List<String> appliedFields
) {
}
