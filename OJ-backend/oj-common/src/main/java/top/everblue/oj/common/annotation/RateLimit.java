package top.everblue.oj.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解。
 * <p>
 * 使用方式：标注在 Controller 方法上即可生效。
 * 例如 {@code @RateLimit(key = "submit", time = 10, count = 1)}
 * 表示同一个用户每 time 秒内最多调用 count 次。
 * </p>
 *
 * <p><b>原理解析（大白话版）：</b><br>
 * Redis 里用到的数据结构是 String（最简单的 KV）。<br>
 * 核心思路：给每个用户维护一把"时间窗口锁"，Key 形如
 * {@code oj:ratelimit:submit:123}，Value 就是当前已请求的次数。<br>
 * 但"先查后写"在高并发下不安全（A 和 B 同时查到 0，都认为自己能过），
 * 所以用 Lua 脚本把"查次数 → 没超就 +1 → 设过期时间"打包成一个原子操作，
 * Redis 单线程执行 Lua 脚本，相当于在 Redis 内部加了一把天然锁。
 * </p>
 *
 * @see top.everblue.oj.common.aop.RateLimitAspect
 */
@Target(ElementType.METHOD)        // 只能贴方法上
@Retention(RetentionPolicy.RUNTIME) // 运行时通过反射读取
public @interface RateLimit {

    /**
     * 限流标识（会拼入 Redis Key，用于区分不同接口）。
     * 例如 "submit"、"run"。
     */
    String key();

    /**
     * 时间窗口大小，单位：秒。默认 10 秒。
     */
    int time() default 10;

    /**
     * 时间窗口内允许的最大请求次数。默认 1 次。
     */
    int count() default 1;

    /**
     * 是否按客户端 IP 限流（默认 false，按登录用户限流）。
     * <p>
     * 登录/注册等<b>未登录场景</b>无法取到 userId，必须按 IP 限流：
     * 设 {@code byIp = true} 后，Redis Key 形如 {@code oj:ratelimit:login:192.168.1.10}。
     * 已登录用户也可指定按 IP 限流（如防止多账号共享设备绕过）。
     * </p>
     */
    boolean byIp() default false;
}
