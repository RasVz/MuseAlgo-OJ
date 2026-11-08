package com.musealgo.oj.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musealgo.oj.common.config.InfrastructureExceptionHandler;
import com.musealgo.oj.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class InfrastructureErrorContractTest {
    @ParameterizedTest
    @CsvSource({"connection,503,DEPENDENCY_UNAVAILABLE", "query_timeout,503,DEPENDENCY_UNAVAILABLE", "transaction,503,DEPENDENCY_UNAVAILABLE", "sql,500,INTERNAL_ERROR", "constraint,500,INTERNAL_ERROR", "unknown,500,INTERNAL_ERROR", "wrapped_connection,503,DEPENDENCY_UNAVAILABLE", "wrapped_sql,500,INTERNAL_ERROR"})
    void onlyKnownAvailabilityFailuresAreRetryable(String kind, int code, String reason) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new Probe())
                .setControllerAdvice(new InfrastructureExceptionHandler(), new GlobalExceptionHandler()).build();
        var response = mvc.perform(get("/infra/" + kind)).andReturn().getResponse();
        var body = new ObjectMapper().readTree(response.getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals(code, body.path("code").asInt());
        assertEquals(reason, body.path("errorCode").asText());
        assertFalse(response.getContentAsString().contains("synthetic-private-sql"));
    }

    @RestController static class Probe {
        @GetMapping("/infra/{kind}") Object fail(@PathVariable("kind") String kind) {
            String message = "synthetic-private-sql";
            throw switch (kind) {
                case "connection" -> new DataAccessResourceFailureException(message);
                case "query_timeout" -> new QueryTimeoutException(message);
                case "transaction" -> new CannotCreateTransactionException(message);
                case "sql" -> new InvalidDataAccessResourceUsageException(message);
                case "constraint" -> new DataIntegrityViolationException(message);
                case "wrapped_connection" -> new org.apache.ibatis.exceptions.PersistenceException(
                        new org.springframework.jdbc.CannotGetJdbcConnectionException(message));
                case "wrapped_sql" -> new org.mybatis.spring.MyBatisSystemException(
                        new InvalidDataAccessResourceUsageException(message));
                default -> new IllegalStateException(message);
            };
        }
    }
}
