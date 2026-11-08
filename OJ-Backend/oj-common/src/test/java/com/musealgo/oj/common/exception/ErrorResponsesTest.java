package com.musealgo.oj.common.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musealgo.oj.common.api.Result;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import static org.junit.jupiter.api.Assertions.*;

class ErrorResponsesTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @AfterEach void clear() { MDC.clear(); }

    @Test void businessReasonRetainsTraceAndSafeDetails() throws Exception {
        MDC.put("traceId", "trace-contract");
        var exception = new BusinessException(new ErrorDefinition("EXAM_ATTEMPT_EXPIRED", 409, "已交卷"),
                "已交卷", Map.of("autoSubmitted", true));
        var result = new GlobalExceptionHandler().handleBusinessException(exception);
        var json = mapper.readTree(mapper.writeValueAsString(result));
        assertEquals(409, json.get("code").asInt());
        assertEquals("EXAM_ATTEMPT_EXPIRED", json.get("errorCode").asText());
        assertEquals("trace-contract", json.get("traceId").asText());
        assertTrue(json.path("details").path("autoSubmitted").asBoolean());
    }

    @Test void validationOmitsRejectedPasswordsAndHandlesEmptyViolations() throws Exception {
        var binding = new BeanPropertyBindingResult(new Object(), "request");
        binding.addError(new FieldError("request", "password", "private-password-value", false,
                new String[]{"Size"}, null, "密码长度不合法"));
        var result = new GlobalExceptionHandler().handleValidationException(new BindException(binding));
        String json = mapper.writeValueAsString(result);
        assertFalse(json.contains("private-password-value"));
        assertTrue(json.contains("fieldErrors"));
        assertEquals(400, new GlobalExceptionHandler().handleConstraintViolationException(
                new jakarta.validation.ConstraintViolationException(Set.of())).getCode());
    }

    @Test void ordinaryJavaFailuresCannotExposeInternalMessages() {
        var handler = new GlobalExceptionHandler();
        assertEquals("系统暂时不可用，请稍后重试", handler.handleIllegalArgumentException(new IllegalArgumentException("/private/server.env")).getMessage());
        assertEquals("INTERNAL_ERROR", handler.handleException(new RuntimeException("SELECT secret")).getErrorCode());
    }

    @Test void legacyFactoriesAndSuccessSerializationRemainCompatible() throws Exception {
        assertEquals(500, Result.error("旧失败").getCode());
        assertEquals(500, Result.fail("旧失败").getCode());
        String success = mapper.writeValueAsString(Result.success("值"));
        assertEquals(mapper.readTree("{\"code\":200,\"message\":\"操作成功\",\"data\":\"值\"}"), mapper.readTree(success));
        assertFalse(success.contains("errorCode"));
    }
    @Test void legacyMapNumbersAreValidatedAtTheRequestBoundary() {
        var invalid = assertThrows(BusinessException.class,
                () -> com.musealgo.oj.common.utils.RequestValues.longValue("secret-invalid-value", "classId"));
        assertEquals("PARAMETER_TYPE_MISMATCH", invalid.getErrorCode().key());
        assertFalse(invalid.getMessage().contains("secret-invalid-value"));
        var missing = assertThrows(BusinessException.class,
                () -> com.musealgo.oj.common.utils.RequestValues.intValue(null, "sort"));
        assertEquals("REQUIRED_PARAMETER_MISSING", missing.getErrorCode().key());
        assertEquals(7L, com.musealgo.oj.common.utils.RequestValues.longValue("7", "id"));
    }
}
