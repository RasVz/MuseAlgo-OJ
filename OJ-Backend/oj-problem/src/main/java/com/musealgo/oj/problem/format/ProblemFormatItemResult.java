package com.musealgo.oj.problem.format;

/** Per-problem formatting outcome; reason is independent of display text. */
public record ProblemFormatItemResult(Long problemId, String title, boolean success, String message,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String errorCode) {
    public ProblemFormatItemResult(Long problemId, String title, boolean success, String message) {
        this(problemId, title, success, message, null);
    }
    public static ProblemFormatItemResult success(Long problemId, String title) {
        return new ProblemFormatItemResult(problemId, title, true, "格式化成功");
    }
    public static ProblemFormatItemResult failure(Long problemId, String message) {
        return failure(problemId, message, "INTERNAL_ERROR");
    }
    public static ProblemFormatItemResult failure(Long problemId, String message, String errorCode) {
        return new ProblemFormatItemResult(problemId, null, false, message, errorCode);
    }
}
