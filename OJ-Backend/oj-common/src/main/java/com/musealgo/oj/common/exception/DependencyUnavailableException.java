package com.musealgo.oj.common.exception;

/** Preserves dependency failure as a rejected operation, not an invalid credential. */
public class DependencyUnavailableException extends BusinessException {
    public DependencyUnavailableException(ErrorCode code, Throwable cause) {
        super(code);
        initCause(cause);
    }
}
