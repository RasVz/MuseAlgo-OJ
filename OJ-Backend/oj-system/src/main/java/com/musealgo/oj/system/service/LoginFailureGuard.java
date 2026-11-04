package com.musealgo.oj.system.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.musealgo.oj.system.entity.User;
import com.musealgo.oj.system.mapper.UserMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 登录失败计数与账号冷却。
 *
 * <p>与按 IP 的 {@code @RateLimit} 配合使用，两者计数口径不同，不能互相替代：</p>
 * <ul>
 *   <li><b>IP 额度</b>（{@code oj.security.login.ip-max-attempts}）：同一出口 IP 在窗口内的
 *       登录请求总数，防止单机爆破；机房共用出口时必须按班级规模放大，否则会误伤整班登录。</li>
 *   <li><b>账号失败</b>（{@code oj.security.login.account-max-failures}）：同一用户名在窗口内的
 *       <b>失败</b>次数，命中后冷却一段时间。它能挡住「换 IP 继续猜同一账号」，
 *       也不会因为正常学生集中登录而触发。</li>
 * </ul>
 *
 * <p>已存在账号按认证查询相同的 username/email 规则解析为 userId，避免邮箱或大小写别名分散计数；
 * 未知账号按输入摘要使用独立桶。失败计数、过期与冷却在一条 Redis Lua 内原子更新。
 * 冷却只拒绝后续请求，触发前已进入认证的请求可能完成，不限制认证并发数。</p>
 *
 * <p>故障策略：<b>fail-open</b>——Redis 异常时放行并记 warn，与 {@code RateLimitAspect} 保持一致，
 * 避免限流组件成为登录单点故障。关闭开关是 {@code account-max-failures=0}（默认值），
 * 此时本服务不产生任何 Redis 写入。</p>
 */
@Slf4j
@Service
public class LoginFailureGuard {

    private static final String FAIL_KEY_PREFIX = "oj:login:fail:";
    private static final String LOCK_KEY_PREFIX = "oj:login:lock:";

    private static final DefaultRedisScript<Long> RECORD_FAILURE = new DefaultRedisScript<>("""
            local locked = redis.call('TTL', KEYS[2])
            if locked > 0 then return locked end
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
            if count >= tonumber(ARGV[1]) then
                redis.call('SET', KEYS[2], '1', 'EX', ARGV[3])
                redis.call('DEL', KEYS[1])
                return tonumber(ARGV[3])
            end
            return 0
            """, Long.class);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private UserMapper userMapper;

    /** 账号失败阈值；0 或负数表示关闭账号维度保护（默认关闭，需显式配置才生效）。 */
    @Value("${oj.security.login.account-max-failures:0}")
    private int accountMaxFailures;

    /** 失败计数窗口（秒）。 */
    @Value("${oj.security.login.account-window-seconds:60}")
    private int accountWindowSeconds;

    /** 触发阈值后的冷却时长（秒），冷却期内该账号直接拒绝。 */
    @Value("${oj.security.login.account-cooldown-seconds:60}")
    private int accountCooldownSeconds;

    /**
     * 查询账号冷却剩余秒数。
     *
     * @param username 用户名；空白直接返回 0
     * @return 剩余冷却秒数，0 表示未锁定或未启用
     */
    public long lockedSeconds(String username) {
        if (!enabled() || isBlank(username)) {
            return 0L;
        }
        String subject = resolveSubject(username);
        try {
            Long ttl = stringRedisTemplate.getExpire(LOCK_KEY_PREFIX + subject);
            return ttl != null && ttl > 0 ? ttl : 0L;
        } catch (RuntimeException e) {
            log.warn("【登录防护】读取账号冷却状态失败，降级放行 username={}：{}", username, e.getMessage());
            return 0L;
        }
    }

    /**
     * 记录一次登录失败；达到阈值时写入冷却标记。
     *
     * @param username 用户名；空白忽略
     */
    public void recordFailure(String username) {
        if (!enabled() || isBlank(username)) {
            return;
        }
        String subject = resolveSubject(username);
        try {
            stringRedisTemplate.execute(RECORD_FAILURE,
                    List.of(FAIL_KEY_PREFIX + subject, LOCK_KEY_PREFIX + subject),
                    String.valueOf(accountMaxFailures), String.valueOf(accountWindowSeconds),
                    String.valueOf(accountCooldownSeconds));
        } catch (RuntimeException e) {
            log.warn("【登录防护】记录失败次数异常，已忽略 username={}：{}", username, e.getMessage());
        }
    }

    /**
     * 登录成功后清除失败计数；保留并发失败可能刚创建的冷却，等待其自然到期。
     *
     * @param username 用户名；空白忽略
     */
    public void clearFailures(String username) {
        if (!enabled() || isBlank(username)) {
            return;
        }
        String subject = resolveSubject(username);
        try {
            stringRedisTemplate.delete(FAIL_KEY_PREFIX + subject);
        } catch (RuntimeException e) {
            log.warn("【登录防护】清理失败计数异常，已忽略 username={}：{}", username, e.getMessage());
        }
    }

    private boolean enabled() {
        return accountMaxFailures > 0;
    }

    private String resolveSubject(String login) {
        // 与 UserDetailsServiceImpl 的查询条件和数据库排序规则保持一致。
        // 数据库错误不按 Redis 故障吞掉，认证数据不可用时应停止登录。
        User user = userMapper.selectOne(new QueryWrapper<User>().select("id")
                .eq("username", login).or().eq("email", login));
        if (user != null) {
            return "user:" + user.getId();
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(login.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return "input:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
