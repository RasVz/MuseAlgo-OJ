package com.musealgo.oj.analytics.application;

/** Stable report-job business error code exposed by the existing Result envelope. */
public class ReportJobException extends RuntimeException {
    private final String errorCode;

    public ReportJobException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
