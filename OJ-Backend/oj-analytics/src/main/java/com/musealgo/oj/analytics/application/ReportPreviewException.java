package com.musealgo.oj.analytics.application;

/** HTTP status and stable error code for the legacy read-only HTML preview URI. */
public class ReportPreviewException extends RuntimeException {
    private final int httpStatus;
    private final String errorCode;

    public ReportPreviewException(int httpStatus, String errorCode) {
        super(errorCode);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public int httpStatus() { return httpStatus; }
    public String errorCode() { return errorCode; }
}
