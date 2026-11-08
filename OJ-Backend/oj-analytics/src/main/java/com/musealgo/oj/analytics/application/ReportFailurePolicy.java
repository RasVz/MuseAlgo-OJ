package com.musealgo.oj.analytics.application;

import org.springframework.dao.DataAccessException;
import com.musealgo.oj.analytics.entity.ReportJob;

import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Stable report failure codes and retry decisions shared by API and worker paths.
 * <p>
 * Two explicit allowlists, as required by the lifecycle contract: only provably transient failures
 * enter automatic retry with the shared three-attempt budget; an explicitly authorised actor may
 * additionally re-enqueue deterministic/unknown terminal failures while the budget remains. Unknown
 * renderer exceptions must never be treated as transient just because their code is not understood.
 */
public final class ReportFailurePolicy {

    public static final int MAX_ATTEMPTS = 3;

    /** Renderer failed with an unexpected exception; no automatic retry, manual retry allowed. */
    public static final String GENERATOR_ERROR = "GENERATOR_ERROR";
    /** Deterministic invalid report input/data; terminal, no retry until the data is corrected. */
    public static final String RENDER_INPUT_INVALID = "RENDER_INPUT_INVALID";

    private static final Set<String> MANUALLY_RETRYABLE = Set.of(
            "AUTH_REVOKED", "LEGACY_ACTOR_MISSING", "GENERATION_TIMEOUT",
            "GENERATOR_UNAVAILABLE", GENERATOR_ERROR, "FINALIZATION_UNAVAILABLE", "DATABASE_TRANSIENT");

    private static final Set<String> AUTOMATICALLY_RETRYABLE = Set.of(
            "GENERATION_TIMEOUT", "GENERATOR_UNAVAILABLE", "FINALIZATION_UNAVAILABLE", "DATABASE_TRANSIENT");

    private ReportFailurePolicy() {
    }

    public static boolean canRetry(ReportJob job) {
        return job != null && "FAILED".equals(job.getStatus())
                && job.getAttempts() != null && job.getAttempts() < MAX_ATTEMPTS
                && MANUALLY_RETRYABLE.contains(effectiveCode(job));
    }

    public static String effectiveCode(ReportJob job) {
        if (job == null) return "GENERATOR_UNAVAILABLE";
        if (job.getLastErrorCode() != null && !job.getLastErrorCode().isBlank()) {
            return job.getLastErrorCode();
        }
        return "LEGACY_FAILURE";
    }

    public static String safeMessage(String code) {
        return switch (code == null ? "" : code) {
            case "AUTH_REVOKED" -> "报告请求主体已失去当前班级权限；有权教师可在预算内重新受理。";
            case "LEGACY_ACTOR_MISSING" -> "历史请求主体已不存在；有权教师可在预算内重新受理。";
            case "EMPTY_RENDER" -> "报告内容为空，未保存为可用报告。";
            case "INVALID_PERIOD" -> "报告周期无效。";
            case "GENERATION_TIMEOUT" -> "报告生成超过运行时限。";
            case "GENERATOR_UNAVAILABLE" -> "报告生成暂时失败。";
            case GENERATOR_ERROR -> "报告生成出现未预期错误；可在剩余预算内手动重试或查看服务日志。";
            case RENDER_INPUT_INVALID -> "报告数据不满足生成条件，修正数据后可重新受理。";
            case "FINALIZATION_UNAVAILABLE" -> "报告结果暂时无法安全保存。";
            case "ATTEMPT_AUDIT_UNAVAILABLE" -> "报告执行记录暂时无法安全核验。";
            case "ATTEMPTS_EXHAUSTED" -> "报告生成次数已耗尽。";
            case "LEGACY_GENERATING_RECOVERED" -> "旧任务已安全回收并排队。";
            case "LEGACY_FAILURE" -> "历史报告生成失败，原因未记录。";
            case "DATABASE_TRANSIENT" -> "报告数据暂时不可用。";
            default -> "报告生成失败。";
        };
    }

    /** Only a demonstrably transient failure may consume budget without an operator decision. */
    public static boolean isAutomaticallyRetryable(String errorCode) {
        return AUTOMATICALLY_RETRYABLE.contains(errorCode);
    }

    /**
     * Classifies a renderer exception by cause. Database and timeout failures are transient; invalid
     * input is deterministic; anything else is unknown and therefore stops at FAILED instead of being
     * silently retried three times.
     */
    public static String classifyRendererFailure(Throwable error) {
        if (hasCause(error, DataAccessException.class) || hasCause(error, SQLException.class)) {
            return "DATABASE_TRANSIENT";
        }
        if (hasCause(error, TimeoutException.class) || hasCause(error, SocketTimeoutException.class)) {
            return "GENERATION_TIMEOUT";
        }
        if (hasCause(error, IllegalArgumentException.class)) {
            return RENDER_INPUT_INVALID;
        }
        return GENERATOR_ERROR;
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = error; current != null && seen.add(current); current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }
}
