package com.musealgo.oj.problem.format;

/** 单题预览结果：AI 已整理好，但尚未写库 */
public record ProblemFormatPreviewItem(
        Long problemId,
        String title,
        boolean success,
        String message,
        ProblemFormattedContent formatted,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String errorCode
) {

    public ProblemFormatPreviewItem(Long problemId, String title, boolean success, String message, ProblemFormattedContent formatted) {
        this(problemId, title, success, message, formatted, null);
    }

    public static ProblemFormatPreviewItem success(Long problemId, ProblemFormattedContent formatted) {
        return new ProblemFormatPreviewItem(problemId, formatted.title(), true, "格式化成功", formatted);
    }

    public static ProblemFormatPreviewItem failure(Long problemId, String message) {
        return failure(problemId, message, "INTERNAL_ERROR");
    }

    public static ProblemFormatPreviewItem failure(Long problemId, String message, String errorCode) {
        return new ProblemFormatPreviewItem(problemId, null, false, message, null, errorCode);
    }
}
