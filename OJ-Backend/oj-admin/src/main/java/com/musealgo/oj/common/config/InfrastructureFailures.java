package com.musealgo.oj.common.config;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** MyBatis may wrap connection failures; only recognized causes imply unavailability. */
public final class InfrastructureFailures {
    private InfrastructureFailures() { }

    public static boolean isUnavailable(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException
                    || cause instanceof QueryTimeoutException
                    || cause instanceof CannotCreateTransactionException) return true;
        }
        return false;
    }
}
