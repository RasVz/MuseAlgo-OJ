package com.musealgo.oj.common.exception;

import com.musealgo.oj.common.api.Result;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Ordinary MVC failures retain HTTP 200; the JSON body carries the classified reason. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public Result<String> handleBusinessException(BusinessException e) {
        log.debug("业务拒绝 errorCode={}", e.getErrorCode().key());
        return ErrorResponses.failure(e.getErrorCode(), e.getMessage(), e.getDetails());
    }

    @ExceptionHandler(RateLimitException.class)
    public Result<String> handleRateLimitException(RateLimitException e) {
        return ErrorResponses.failure(CommonErrorCode.RATE_LIMITED, e.getMessage(),
                Map.of("retryAfterSeconds", Math.max(0, e.getRemainingSeconds())));
    }

    @ExceptionHandler(BindException.class)
    public Result<String> handleValidationException(BindException e) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream().limit(20)
                .map(error -> fieldError(error.getField(), error.getCode(), error.getDefaultMessage())).toList();
        return validationFailure(errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public Result<String> handleConstraintViolationException(ConstraintViolationException e) {
        List<Map<String, String>> errors = e.getConstraintViolations().stream().limit(20)
                .map(error -> fieldError(error.getPropertyPath().toString(),
                        error.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName(),
                        error.getMessage())).toList();
        return validationFailure(errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public Result<String> handleMethodValidation(HandlerMethodValidationException e) {
        if (e.isForReturnValue()) return handleException(e);
        List<Map<String, String>> errors = e.getAllValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream().map(error -> fieldError(
                        result.getMethodParameter().getParameterName(), "Invalid", error.getDefaultMessage())))
                .limit(20).toList();
        return validationFailure(errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<String> handleUnreadableBody(HttpMessageNotReadableException e) {
        return ErrorResponses.failure(CommonErrorCode.REQUEST_BODY_INVALID);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public Result<String> handleMissingParameter(Exception e) {
        return ErrorResponses.failure(CommonErrorCode.REQUIRED_PARAMETER_MISSING);
    }

    @ExceptionHandler(TypeMismatchException.class)
    public Result<String> handleTypeMismatch(TypeMismatchException e) {
        return ErrorResponses.failure(CommonErrorCode.PARAMETER_TYPE_MISMATCH);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public Result<String> handleNoHandler(NoHandlerFoundException e) {
        return ErrorResponses.failure(CommonErrorCode.ROUTE_NOT_FOUND);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public Result<String> handleNoResource(NoResourceFoundException e) {
        return ErrorResponses.failure(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<String> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ErrorResponses.failure(CommonErrorCode.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public Result<String> handleUnsupportedMedia(HttpMediaTypeNotSupportedException e) {
        return ErrorResponses.failure(CommonErrorCode.MEDIA_TYPE_UNSUPPORTED);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public org.springframework.http.ResponseEntity<Void> handleUnacceptableMedia(HttpMediaTypeNotAcceptableException e) {
        // No representation is acceptable; another JSON conversion would fail again.
        return org.springframework.http.ResponseEntity.status(406).build();
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<String> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return ErrorResponses.failure(CommonErrorCode.UPLOAD_TOO_LARGE);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<String> handleIllegalArgumentException(IllegalArgumentException e) {
        // Input rejections must be explicit at their boundary; a Java programming exception is a server fault.
        return handleException(e);
    }

    @ExceptionHandler(ForbiddenException.class)
    public Result<String> handleForbiddenException(ForbiddenException e) {
        return ErrorResponses.failure(CommonErrorCode.ACCESS_DENIED, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<String> handleException(Exception e) {
        log.error("未处理的异常 errorCode=INTERNAL_ERROR", e);
        return ErrorResponses.failure(CommonErrorCode.INTERNAL_ERROR);
    }

    private Result<String> validationFailure(List<Map<String, String>> errors) {
        String message = errors.isEmpty() ? CommonErrorCode.VALIDATION_FAILED.message() : errors.get(0).get("message");
        return ErrorResponses.failure(CommonErrorCode.VALIDATION_FAILED, message, Map.of("fieldErrors", errors));
    }

    private Map<String, String> fieldError(String field, String code, String message) {
        return Map.of("field", bounded(field, "parameter"), "code", bounded(code, "Invalid"),
                "message", bounded(message, CommonErrorCode.VALIDATION_FAILED.message()));
    }

    private String bounded(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.substring(0, Math.min(256, value.length()));
    }
}
