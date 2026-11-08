package com.musealgo.oj.common.config;

import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.common.exception.CommonErrorCode;
import com.musealgo.oj.common.exception.ErrorResponses;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Only recognized availability failures are 503; SQL/constraint programming errors retain the 500 fallback. */
@Slf4j
@Order(-100)
@RestControllerAdvice
public class InfrastructureExceptionHandler {
    @ExceptionHandler({DataAccessResourceFailureException.class, QueryTimeoutException.class, CannotCreateTransactionException.class,
            org.apache.ibatis.exceptions.PersistenceException.class, org.mybatis.spring.MyBatisSystemException.class})
    public Result<String> unavailable(Exception cause) {
        if (!InfrastructureFailures.isUnavailable(cause)) {
            log.error("持久化异常 errorCode=INTERNAL_ERROR", cause);
            return ErrorResponses.failure(CommonErrorCode.INTERNAL_ERROR);
        }
        log.error("依赖不可用 errorCode=DEPENDENCY_UNAVAILABLE", cause);
        return ErrorResponses.failure(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
    }
}
