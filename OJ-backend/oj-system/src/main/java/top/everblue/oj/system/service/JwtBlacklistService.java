package top.everblue.oj.system.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * JWT 注销黑名单服务（v1：按 jti 单 token 拉黑）。
 * <p>
 * 键：{@code oj:jwt:blacklist:{jti}}，TTL = token 剩余有效期，到期自动消失。
 * <p>
 * 故障策略：<b>fail-open</b>——Redis 异常时 {@link #isRevoked} 返回
 * false 并记 warn，保证 Redis 抖动不影响正常登录态（内网教学平台可用性优先）。
 */
@Slf4j
@Service
public class JwtBlacklistService {

    private static final String KEY_PREFIX = "oj:jwt:blacklist:";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 拉黑指定 jti（幂等：重复拉黑覆盖 TTL）。
     *
     * @param jti      JWT ID；null/空白静默忽略
     * @param ttlMillis 黑名单保留时长（= token 剩余有效期）；≤0 时按 1 分钟兜底
     * @throws IllegalStateException Redis 不可用时抛出（调用方决定是否降级）
     */
    public void revoke(String jti, long ttlMillis) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        long ttl = Math.max(ttlMillis, 60_000L);
        stringRedisTemplate.opsForValue()
                .set(KEY_PREFIX + jti, String.valueOf(System.currentTimeMillis()), ttl, TimeUnit.MILLISECONDS);
        log.info("JWT 已注销: jti={}, ttl={}ms", jti, ttl);
    }

    /**
     * 查询 jti 是否已被拉黑。
     *
     * @param jti JWT ID；null/空白返回 false
     * @return true=已拉黑；Redis 异常时 fail-open 返回 false 并记 warn
     */
    public boolean isRevoked(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(KEY_PREFIX + jti));
        } catch (Exception e) {
            // fail-open：Redis 异常时放行（不阻断正常请求），仅记录
            log.warn("JWT 黑名单检查失败（fail-open 放行）: jti={}, err={}", jti, e.getMessage());
            return false;
        }
    }
}
