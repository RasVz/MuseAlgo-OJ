package com.musealgo.oj.common.exception;

/** Stable public reason, coarse category, and safe default user message. */
public interface ErrorCode {
    String key();
    int status();
    String message();
}
