package com.musealgo.oj.analytics.application;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import com.musealgo.oj.analytics.entity.ReportJob;
import com.musealgo.oj.analytics.report.ReportRenderException;

import java.sql.SQLTransientConnectionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure classification decides whether a report is retried silently or stops at FAILED. These
 * cases pin the contract rule: only provably transient failures auto-retry; unknown renderer
 * exceptions fail once and stay visible for an explicit operator retry.
 */
class ReportFailurePolicyTest {

    @Test
    void databaseAndTimeoutCausesStayAutomaticallyRetryable() {
        assertEquals("DATABASE_TRANSIENT",
                ReportFailurePolicy.classifyRendererFailure(
                        new TransientDataAccessResourceException("pool exhausted")));
        assertEquals("DATABASE_TRANSIENT",
                ReportFailurePolicy.classifyRendererFailure(
                        new IllegalStateException("wrapped", new SQLTransientConnectionException("gone"))));
        assertEquals("GENERATION_TIMEOUT",
                ReportFailurePolicy.classifyRendererFailure(new TimeoutException("render deadline")));
        // Spring's query timeout is a DataAccessException, so it stays in the database-transient bucket.
        assertEquals("DATABASE_TRANSIENT",
                ReportFailurePolicy.classifyRendererFailure(new QueryTimeoutException("query deadline")));
        assertTrue(ReportFailurePolicy.isAutomaticallyRetryable("DATABASE_TRANSIENT"));
        assertTrue(ReportFailurePolicy.isAutomaticallyRetryable("GENERATION_TIMEOUT"));
    }

    @Test
    void unknownRendererExceptionIsNotTreatedAsTransient() {
        String code = ReportFailurePolicy.classifyRendererFailure(
                new ReportRenderException("报告模板渲染失败", new IllegalStateException("template bug")));

        assertEquals("GENERATOR_ERROR", code);
        assertFalse(ReportFailurePolicy.isAutomaticallyRetryable(code));
    }

    @Test
    void unknownFailureStaysVisibleAndAllowsExplicitRetryWithinBudget() {
        ReportJob job = failedJob("GENERATOR_ERROR", 1);

        assertTrue(ReportFailurePolicy.canRetry(job));
        assertEquals("报告生成出现未预期错误；可在剩余预算内手动重试或查看服务日志。",
                ReportFailurePolicy.safeMessage("GENERATOR_ERROR"));
    }

    @Test
    void unknownFailureStopsWhenBudgetIsExhausted() {
        assertFalse(ReportFailurePolicy.canRetry(failedJob("GENERATOR_ERROR", 3)));
    }

    @Test
    void deterministicInvalidInputIsTerminal() {
        String code = ReportFailurePolicy.classifyRendererFailure(
                new IllegalArgumentException("student is not in class"));

        assertEquals("RENDER_INPUT_INVALID", code);
        assertFalse(ReportFailurePolicy.isAutomaticallyRetryable(code));
        assertFalse(ReportFailurePolicy.canRetry(failedJob(code, 1)));
    }

    @Test
    void emptyRenderAndLegacyFailuresCannotBeRetried() {
        assertFalse(ReportFailurePolicy.canRetry(failedJob("EMPTY_RENDER", 1)));
        assertFalse(ReportFailurePolicy.canRetry(failedJob(null, 1)));
        assertEquals("LEGACY_FAILURE", ReportFailurePolicy.effectiveCode(failedJob(null, 1)));
    }

    private static ReportJob failedJob(String code, int attempts) {
        ReportJob job = new ReportJob();
        job.setStatus("FAILED");
        job.setAttempts(attempts);
        job.setLastErrorCode(code);
        return job;
    }
}
