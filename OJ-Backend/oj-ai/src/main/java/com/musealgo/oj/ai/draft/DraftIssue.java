package com.musealgo.oj.ai.draft;

/**
 * 一条可解释的质量问题。
 *
 * @param code     稳定代码，供前端定位与测试断言
 * @param field    title/content/inputDesc/outputDesc/hint/tags/samples/difficultyScore/timeLimit/memoryLimit
 * @param severity 级别
 * @param message  面向教师的中文说明，必须说明「哪里不对」而不是「可能有问题」
 */
public record DraftIssue(String code, String field, DraftSeverity severity, String message) {

    public static DraftIssue error(String code, String field, String message) {
        return new DraftIssue(code, field, DraftSeverity.ERROR, message);
    }

    public static DraftIssue warn(String code, String field, String message) {
        return new DraftIssue(code, field, DraftSeverity.WARN, message);
    }

    public static DraftIssue info(String code, String field, String message) {
        return new DraftIssue(code, field, DraftSeverity.INFO, message);
    }
}
