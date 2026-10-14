package top.everblue.oj.common.aop;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import top.everblue.oj.common.annotation.RateLimit;
import top.everblue.oj.common.exception.RateLimitException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RateLimitAspect 切面单测：覆盖「按 IP 限流（登录/注册未登录场景）」
 * 与「按用户限流（原有行为）」两条路径。
 */
class RateLimitAspectTest {

    private RateLimitAspect aspect;
    private StringRedisTemplate redis;
    private ProceedingJoinPoint joinPoint;
    private RateLimit annotation;

    @BeforeEach
    void setUp() throws Throwable {
        aspect = new RateLimitAspect();
        redis = mock(StringRedisTemplate.class);
        ReflectionTestUtils.setField(aspect, "stringRedisTemplate", redis);

        joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn("ok");

        annotation = mock(RateLimit.class);
        when(annotation.key()).thenReturn("authLogin");
        when(annotation.time()).thenReturn(60);
        when(annotation.count()).thenReturn(10);

        // 默认未登录（SecurityContext 无认证信息）
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void anonymousByIpUsesClientIpAsKey() throws Throwable {
        when(annotation.byIp()).thenReturn(true);
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.8");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        aspect.around(joinPoint, annotation);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(DefaultRedisScript.class), keysCaptor.capture(), any(Object[].class));
        assertEquals(List.of("oj:ratelimit:authLogin:10.0.0.8"), keysCaptor.getValue());
    }

    @Test
    void anonymousByIpPrefersForwardedHeaderFirstValue() throws Throwable {
        when(annotation.byIp()).thenReturn(true);
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.8");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        aspect.around(joinPoint, annotation);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(DefaultRedisScript.class), keysCaptor.capture(), any(Object[].class));
        assertEquals(List.of("oj:ratelimit:authLogin:203.0.113.7"), keysCaptor.getValue());
    }

    @Test
    void anonymousByIpThrowsWhenExceeded() throws Throwable {
        when(annotation.byIp()).thenReturn(true);
        // Lua 返回剩余等待秒数 = 超限
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(37L);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        RateLimitException ex = assertThrows(RateLimitException.class, () -> aspect.around(joinPoint, annotation));
        assertTrue(ex.getMessage().contains("37"));
    }

    @Test
    void anonymousWithoutByIpIsAllowed() throws Throwable {
        // 默认 byIp=false + 未登录 → 直接放行，不触碰 Redis
        Object result = aspect.around(joinPoint, annotation);
        assertEquals("ok", result);
        verify(redis, never()).execute(any(), anyList(), any(Object[].class));
    }

    @Test
    void loggedInUserLimitedByUserId() throws Throwable {
        when(annotation.byIp()).thenReturn(false);
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

        // principal 需实现 AuthenticatedUser（与真实 LoginUser 一致），getUserId 才能取到 ID
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken((top.everblue.oj.common.security.AuthenticatedUser) () -> 42L,
                        null, List.of()));

        aspect.around(joinPoint, annotation);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(DefaultRedisScript.class), keysCaptor.capture(), any(Object[].class));
        assertEquals(List.of("oj:ratelimit:authLogin:42"), keysCaptor.getValue());
    }
}
