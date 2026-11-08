package com.musealgo.oj.common.exception;

import lombok.Getter;

/**
 * 限流异常。
 * <p>
 * 当用户请求频率超过 {@code @RateLimit} 注解设定的阈值时，
 * 由 {@link com.musealgo.oj.common.aop.RateLimitAspect} 切面抛出。
 * 最终被 {@link GlobalExceptionHandler} 统一处理为 429 状态码的 JSON 响应。
 * </p>
 */
@Getter
public class RateLimitException extends RuntimeException {

    /**
     * 还需要等待多少秒才能再次请求。
     * 前端可用此值做倒计时提示。
     */
    private final long remainingSeconds;

    public RateLimitException(long remainingSeconds) {
        super("提交过于频繁，请 " + remainingSeconds + " 秒后再试");
        this.remainingSeconds = remainingSeconds;
    }

    /**
     * 带动作名的超限异常，用于登录/注册等非提交场景的文案。
     *
     * @param remainingSeconds 还需等待的秒数
     * @param action           动作名（如「登录」）；空白时沿用默认文案
     */
    public RateLimitException(long remainingSeconds, String action) {
        super(action == null || action.isBlank()
                ? "提交过于频繁，请 " + remainingSeconds + " 秒后再试"
                : action + "过于频繁，请 " + remainingSeconds + " 秒后再试");
        this.remainingSeconds = remainingSeconds;
    }
}
