package com.musealgo.oj.ai.network;

/** Stable, safe reasons; never exposes resolver responses. */
public class AiEndpointException extends IllegalArgumentException {
    private final String errorCode;
    private final int status;

    public AiEndpointException(String errorCode, int status, String message) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public String errorCode() { return errorCode; }
    public int status() { return status; }
}
