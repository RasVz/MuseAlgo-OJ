package top.everblue.oj.common.aop;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import top.everblue.oj.common.annotation.RateLimit;
import top.everblue.oj.common.exception.RateLimitException;
import top.everblue.oj.common.utils.SecurityUtils;

import java.util.Collections;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 限流切面 —— 拦截所有标注了 {@code @RateLimit} 的方法。
 *
 * <h3>原理解析（大白话版）</h3>
 * <ol>
 *   <li><b>用了 Redis 的什么数据结构？</b><br>
 *       String（最简单的 KV）。每个 Key 对应一个用户在一个接口上的"请求计数器"。
 *       例如 {@code oj:ratelimit:submit:123} → 值 "1"，TTL 还剩 7 秒。
 *   </li>
 *   <li><b>用到了哪些 Redis 命令？</b><br>
 *       在 Lua 脚本中组合使用：{@code GET}（读当前次数）、{@code INCR}（次数+1）、
 *       {@code EXPIRE}（设过期时间）、{@code TTL}（查剩余过期秒数）。
 *   </li>
 *   <li><b>为什么用 Lua 而不是 Java 里"先查后写"？</b><br>
 *       Redis 单线程执行 Lua 脚本是原子的 —— 脚本执行期间其他命令都排队等着。
 *       这保证了"判断 + 计数 + 设过期"一气呵成，不会出现竞态条件。
 *       Java 里分开调用 GET → 判断 → INCR 的话，两步之间可能插入其他请求，
 *       造成"两个人都以为自己能过"的并发 BUG。
 *   </li>
 * </ol>
 *
 * <h3>并发安全警告（重点）</h3>
 * <p>
 * 高并发下，下面的 <b>错误写法</b> 是危险的：
 * <pre>{@code
 * // 错误写法：先查后写，存在竞态条件
 * String val = redis.opsForValue().get(key);
 * if (val != null && Integer.parseInt(val) >= count) {
 *     throw new RateLimitException(...);
 * }
 * redis.opsForValue().increment(key);
 * }</pre>
 * 原因：线程 A 和线程 B 同时执行 GET 都拿到 null（或 0），然后都认为自己没超限，
 * 双双执行 INCR，限流失效。
 * </p>
 * <p>
 * 本类的 <b>正确做法</b>：把整个判断+计数逻辑写进一段 Lua 脚本，
 * 通过 {@code redisTemplate.execute(script, keys, args)} 一次性发送给 Redis。
 * Redis 单线程串行执行 Lua，天然避免了并发问题。
 * </p>
 *
 * <h3>Redis 宕机兜底策略</h3>
 * <p>
 * 所有 Redis 操作包裹在 try-catch 中。如果 Redis 连不上或超时：
 * <ul>
 *   <li>打一条 WARN 日志记录异常</li>
 *   <li>直接放行请求（fail-open）</li>
 *   <li>宁可短时间内不设防，也不能因为限流组件故障导致正常业务中断</li>
 * </ul>
 * </p>
 */
@Slf4j
@Aspect
@Component
public class RateLimitAspect {

    // ==================== 被注入的 Redis 操作模板 ====================
    // StringRedisTemplate：操作 Redis String 类型，Key/Value 都是 String。
    // 这里用它而不是 RedisTemplate<String, Object>，因为我们的计数器就是数字字符串，简单高效。
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // ==================== Lua 脚本（核心） ====================
    //
    // KEYS[1]  — 限流 Key，例如 "oj:ratelimit:submit:123"
    // ARGV[1]  — 时间窗口内最大允许次数（count）
    // ARGV[2]  — 时间窗口秒数（time）
    //
    // 返回值：
    //   0     → 未超限，放行
    //  >0    → 已超限，返回值是还需等待的秒数（即 Key 的剩余 TTL）
    //
    // 执行流程：
    //   1. GET 查看当前计数器
    //   2. 如果已经 >= 限制次数 → 返回剩余 TTL
    //   3. 否则 INCR 计数器 +1
    //   4. 如果是第一次（INCR 返回 1），设置 EXPIRE 过期时间
    //   5. 返回 0 表示放行
    private static final String LUA_SCRIPT =
              "local current = redis.call('GET', KEYS[1]) "                // 读当前计数
            + "if current and tonumber(current) >= tonumber(ARGV[1]) then " // 已经 >= 限制？
            + "    return redis.call('TTL', KEYS[1]) "                     // 返回还需等待秒数
            + "end "
            + "local count = redis.call('INCR', KEYS[1]) "                 // 安全：次数 +1
            + "if count == 1 then "                                         // 第一次创建 Key？
            + "    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2])) "      // 设过期时间
            + "end "
            + "return 0";                                                    // 0 = 放行

    // 将脚本字符串包装成 Spring 能执行的 DefaultRedisScript 对象
    // 泛型 <Long> 表示脚本返回值类型（TTL 秒数或 0）
    private static final DefaultRedisScript<Long> REDIS_SCRIPT;

    static {
        REDIS_SCRIPT = new DefaultRedisScript<>();
        REDIS_SCRIPT.setScriptText(LUA_SCRIPT);
        REDIS_SCRIPT.setResultType(Long.class); // 告诉 Spring：返回值转 Long
    }

    // ==================== 切面逻辑 ====================

    /**
     * 环绕通知：拦截所有贴了 @RateLimit 的方法。
     *
     * @param joinPoint 切点（包含被拦截方法的所有信息）
     * @param rateLimit 被拦截方法上的 @RateLimit 注解实例
     * @return 原方法的返回值
     * @throws Throwable 业务异常或限流异常
     */
    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {

        // --- 第一步：构建 Redis Key ---
        // Key 格式：oj:ratelimit:{注解的key}:{用户ID 或 客户端IP}
        // 例如：oj:ratelimit:submit:123 / oj:ratelimit:login:192.168.1.10
        String subject;
        if (rateLimit.byIp()) {
            // 按 IP 限流（登录/注册等未登录场景必须；防共享设备绕过）
            subject = resolveClientIp();
        } else {
            Long userId = SecurityUtils.getUserId();
            if (userId == null) {
                // 未登录用户：直接放行。
                // 原因：/submission/submit 本身会校验 userId，这里不做限流也不会有安全问题。
                return joinPoint.proceed();
            }
            subject = String.valueOf(userId);
        }

        String redisKey = String.format("oj:ratelimit:%s:%s", rateLimit.key(), subject);

        // --- 第二步：执行 Lua 脚本（原子限流判断） ---
        long result;
        try {
            // execute(脚本对象, KEYS列表, ARGV参数...)
            // 整个 Lua 脚本在 Redis 内部原子执行，不会被其他命令打断
            Long execResult = stringRedisTemplate.execute(
                    REDIS_SCRIPT,
                    Collections.singletonList(redisKey),         // KEYS[1]
                    String.valueOf(rateLimit.count()),           // ARGV[1]
                    String.valueOf(rateLimit.time())             // ARGV[2]
            );
            // execResult 可能为 null（极端情况，如 Redis 脚本执行异常），安全兜底为 0
            result = execResult != null ? execResult : 0;
        } catch (Exception e) {
            // ========== Redis 宕机兜底（fail-open）==========
            // 为什么这样设计？
            // 限流是"锦上添花"的保护手段，不能反客为主变成业务的单点故障。
            // 如果 Redis 挂了就拒绝所有请求，那 Redis 的可用性反而绑架了整个判题系统。
            // 正确的工程实践：Redis 不可用时打日志，然后放行请求。
            log.warn("【限流】Redis 操作异常，已降级放行本次请求。Key={}, 异常信息={}",
                    redisKey, e.getMessage());
            // 直接把请求放给业务方法
            return joinPoint.proceed();
        }

        // --- 第三步：根据脚本返回值决定放行还是拦截 ---
        if (result == 0) {
            // result == 0 → Lua 脚本执行了 INCR，说明未超限，放行
            return joinPoint.proceed();
        } else {
            // result > 0 → Lua 脚本返回了 TTL，说明已超限
            // result 就是还需要等待的秒数
            log.info("【限流】用户 {} 对接口 {} 请求过于频繁，需等待 {} 秒",
                    subject, rateLimit.key(), result);
            throw new RateLimitException(result);
        }
    }

    /**
     * 解析客户端真实 IP。
     * <p>
     * 依次尝试 {@code X-Forwarded-For}（取第一个，防伪造场景由反代覆盖）、
     * {@code X-Real-IP}、{@code getRemoteAddr()}。取不到请求上下文时兜底 "unknown"
     * （极端场景下所有此类请求共享同一把锁，宁可误伤不放开）。
     * </p>
     *
     * @return 客户端 IP 字符串
     */
    private String resolveClientIp() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return "unknown";
        }
        HttpServletRequest request = attrs.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
