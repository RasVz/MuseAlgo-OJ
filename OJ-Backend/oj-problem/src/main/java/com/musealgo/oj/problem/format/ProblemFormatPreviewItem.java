package com.musealgo.oj.problem.format;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 单题整理结果：AI 已整理好，但尚未写库。
 * <p>
 * {@code source} 是执行本次整理时读到的原文快照，前端用它做左右对照；
 * {@code baseHash} 是同一份快照的哈希，应用时必须原样回传，服务端据此拒绝陈旧结果。
 * </p>
 */
public record ProblemFormatPreviewItem(
        Long problemId,
        String title,
        boolean success,
        String message,
        ProblemFormattedContent formatted,
        ProblemStatementSnapshot source,
        List<StatementIssue> issues,
        String baseHash,
        @JsonInclude(JsonInclude.Include.NON_NULL) String errorCode
) {

    public static ProblemFormatPreviewItem success(Long problemId, ProblemFormattedContent formatted,
                                                   ProblemStatementSnapshot source, String baseHash,
                                                   List<StatementIssue> issues) {
        return new ProblemFormatPreviewItem(problemId, formatted.title(), true, "整理完成",
                formatted, source, issues == null ? List.of() : issues, baseHash, null);
    }

    public static ProblemFormatPreviewItem failure(Long problemId, String message, String errorCode) {
        return new ProblemFormatPreviewItem(problemId, null, false, message, null, null, List.of(), null, errorCode);
    }
}
