package com.musealgo.oj.common.exception;

/** Adapter for existing controlled reason catalogs (reports/notifications). Never use raw vendor codes. */
public record ErrorDefinition(String key, int status, String message) implements ErrorCode {
    public ErrorDefinition {
        if (key == null || !key.matches("[A-Z][A-Z0-9_]{0,95}") || status < 400 || status > 599) {
            throw new IllegalArgumentException("Invalid public error definition");
        }
    }
}
