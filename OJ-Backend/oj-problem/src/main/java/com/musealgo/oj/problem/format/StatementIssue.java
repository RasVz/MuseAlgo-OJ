package com.musealgo.oj.problem.format;

/**
 * 一条题面质量问题。
 *
 * @param code     稳定的问题码（前端按码做定位与文案，不解析 message）
 * @param field    所属字段：title / content / inputDesc / outputDesc / hint / limits / samples
 * @param severity 级别；只有 ERROR 阻断「应用并保存」
 * @param message  给教师看的一句话说明
 */
public record StatementIssue(String code, String field, IssueSeverity severity, String message) {

    public static StatementIssue error(String code, String field, String message) {
        return new StatementIssue(code, field, IssueSeverity.ERROR, message);
    }

    public static StatementIssue warn(String code, String field, String message) {
        return new StatementIssue(code, field, IssueSeverity.WARN, message);
    }

    public static StatementIssue info(String code, String field, String message) {
        return new StatementIssue(code, field, IssueSeverity.INFO, message);
    }
}
