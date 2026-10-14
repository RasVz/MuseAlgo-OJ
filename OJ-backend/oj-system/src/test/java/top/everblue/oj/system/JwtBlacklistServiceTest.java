package top.everblue.oj.system;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import top.everblue.oj.system.service.JwtBlacklistService;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JWT 注销黑名单服务单测：revoke 幂等/兜底 TTL、isRevoked 命中、fail-open 降级。
 */
class JwtBlacklistServiceTest {

    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOps;
    private JwtBlacklistService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        service = new JwtBlacklistService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
    }

    @Test
    void revoke_正常拉黑按TTL设置() {
        service.revoke("jti-1", 7200_000L);
        verify(valueOps).set(anyString(), anyString(), anyLong(), org.mockito.ArgumentMatchers.eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void revoke_空jti静默忽略() {
        service.revoke(null, 1000L);
        service.revoke("   ", 1000L);
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void revoke_TTL非正数兜底1分钟() {
        service.revoke("jti-2", 0L);
        verify(valueOps).set(anyString(), anyString(), org.mockito.ArgumentMatchers.eq(60_000L), any());
    }

    @Test
    void isRevoked_命中返回true() {
        when(stringRedisTemplate.hasKey("oj:jwt:blacklist:jti-3")).thenReturn(true);
        assertTrue(service.isRevoked("jti-3"));
    }

    @Test
    void isRevoked_未命中返回false() {
        when(stringRedisTemplate.hasKey("oj:jwt:blacklist:jti-4")).thenReturn(false);
        assertFalse(service.isRevoked("jti-4"));
    }

    @Test
    void isRevoked_redis异常failOpen放行() {
        when(stringRedisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
        assertFalse(service.isRevoked("jti-5")); // fail-open：不阻断请求
    }
}
