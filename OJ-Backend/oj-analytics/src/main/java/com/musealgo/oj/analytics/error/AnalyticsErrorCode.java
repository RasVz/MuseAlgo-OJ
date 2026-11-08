package com.musealgo.oj.analytics.error;

import com.musealgo.oj.common.exception.ErrorCode;

/** Stable public reasons owned by this module. */
public enum AnalyticsErrorCode implements ErrorCode {
    ANALYTICS_INPUT_INVALID(400, "请求参数不合法"),
    INVALID_PERIOD(400, "INVALID_PERIOD"),
    REPORT_EXTERNAL_SEND_DISABLED(409, "禁止关闭外部 dry-run-only"),
    REPORT_JOB_NOT_FOUND(404, "REPORT_JOB_NOT_FOUND"),
    REPORT_NOT_FOUND(404, "报告不存在"),
    REPORT_NOT_READY(409, "REPORT_NOT_READY");

    private final int status;
    private final String message;
    AnalyticsErrorCode(int status, String message) { this.status = status; this.message = message; }
    public String key() { return name(); }
    public int status() { return status; }
    public String message() { return message; }
}
