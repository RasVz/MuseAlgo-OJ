package com.musealgo.oj.analytics.application;

/** HTTP status and stable error code for the legacy read-only HTML preview URI. */
public class ReportPreviewException extends com.musealgo.oj.common.exception.BusinessException {
    private final int httpStatus;
    private final String errorCode;

    public ReportPreviewException(int httpStatus, String errorCode) {
        super(new com.musealgo.oj.common.exception.ErrorDefinition(errorCode, httpStatus, errorCode));
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public int httpStatus() { return httpStatus; }
    public String errorCode() { return errorCode; }
}
