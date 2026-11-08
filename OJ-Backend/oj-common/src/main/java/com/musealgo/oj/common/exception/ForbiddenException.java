package com.musealgo.oj.common.exception;

/** Raised when an authenticated caller lacks permission for a domain resource. */
public class ForbiddenException extends BusinessException {
    public ForbiddenException(String message) {
        super(CommonErrorCode.ACCESS_DENIED, message);
    }
}
