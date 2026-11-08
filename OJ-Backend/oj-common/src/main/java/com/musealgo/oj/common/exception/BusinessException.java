package com.musealgo.oj.common.exception;

import java.util.Map;
import java.util.Objects;

/** Expected rejection with explicitly safe public text and details. */
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final Map<String, ?> details;

    public BusinessException(ErrorCode errorCode) { this(errorCode, errorCode.message()); }
    public BusinessException(ErrorCode errorCode, String safeMessage) { this(errorCode, safeMessage, Map.of()); }
    public BusinessException(ErrorCode errorCode, String safeMessage, Map<String, ?> details) {
        super(safeMessage);
        this.errorCode = Objects.requireNonNull(errorCode);
        this.details = Map.copyOf(details);
    }
    public ErrorCode getErrorCode() { return errorCode; }
    public Map<String, ?> getDetails() { return details; }
}
