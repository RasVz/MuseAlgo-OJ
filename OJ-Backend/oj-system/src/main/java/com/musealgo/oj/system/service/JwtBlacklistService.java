package com.musealgo.oj.system.service;

import lombok.extern.slf4j.Slf4j;
import com.musealgo.oj.common.exception.CommonErrorCode;
import com.musealgo.oj.common.exception.DependencyUnavailableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * JWT 注销黑名单服务（v1：按 jti 单 token 拉黑）。
 * <p>
 * 键：{@code oj:jwt:blacklist:{jti}}，TTL = token 剩余有效期，到期自动消失。
 * <p>
 * 故障策略：fail-closed——Redis 异常时拒绝通过黑名单检查，避免已撤销会话恢复授权。
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
     * @return true=已拉黑；Redis 故障抛出受控不可用异常，调用方必须拒绝授权
     */
    public boolean isRevoked(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        try {
            Boolean revoked = stringRedisTemplate.hasKey(KEY_PREFIX + jti);
            if (revoked == null) throw new IllegalStateException("JWT revocation lookup returned no result");
            return revoked;
        } catch (Exception e) {
            log.warn("JWT 黑名单检查失败（fail-closed 拒绝授权）: jti={}, err={}", jti, e.getMessage());
            throw new DependencyUnavailableException(CommonErrorCode.AUTH_DEPENDENCY_UNAVAILABLE, e);
        }
    }
}
