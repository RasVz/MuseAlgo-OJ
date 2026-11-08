package com.musealgo.oj.analytics.application;

/** Stable report-job business error code exposed by the existing Result envelope. */
public class ReportJobException extends com.musealgo.oj.common.exception.BusinessException {
    private final String errorCode;

    public ReportJobException(String errorCode) {
        super(new com.musealgo.oj.common.exception.ErrorDefinition(errorCode, 409, errorCode));
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
