package com.musealgo.oj.common.exception;

import com.musealgo.oj.common.api.Result;
import java.util.Map;
import org.slf4j.MDC;

/** Builds failures without changing HTTP status or success payloads. */
public final class ErrorResponses {
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    private ErrorResponses() { }

    public static void write(jakarta.servlet.http.HttpServletResponse response, int status, ErrorCode code)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        MAPPER.writeValue(response.getOutputStream(), failure(code));
    }

    public static <T> Result<T> copyFailure(Result<?> source) {
        Result<T> result = new Result<>();
        result.setCode(source.getCode());
        result.setErrorCode(source.getErrorCode());
        result.setMessage(source.getMessage());
        result.setTraceId(source.getTraceId());
        result.setDetails(source.getDetails());
        return result;
    }

    public static <T> Result<T> failure(ErrorCode code) { return failure(code, code.message()); }
    public static <T> Result<T> failure(ErrorCode code, String safeMessage) {
        return failure(code, safeMessage, Map.of());
    }
    public static <T> Result<T> failure(ErrorCode code, String safeMessage, Map<String, ?> details) {
        Result<T> result = new Result<>();
        result.setCode(code.status());
        result.setErrorCode(code.key());
        result.setMessage(safeMessage == null || safeMessage.isBlank() ? code.message() : safeMessage);
        result.setTraceId(MDC.get("traceId"));
        if (!details.isEmpty()) result.setDetails(Map.copyOf(details));
        return result;
    }
}
