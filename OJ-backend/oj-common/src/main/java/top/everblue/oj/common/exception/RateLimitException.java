package top.everblue.oj.common.exception;

import lombok.Getter;

/**
 * 限流异常。
 * <p>
 * 当用户请求频率超过 {@code @RateLimit} 注解设定的阈值时，
 * 由 {@link top.everblue.oj.common.aop.RateLimitAspect} 切面抛出。
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
}
