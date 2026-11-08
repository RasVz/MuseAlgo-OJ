package com.musealgo.oj.system.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.musealgo.oj.system.entity.User;
import com.musealgo.oj.system.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoginFailureGuardTest {
    @Mock private StringRedisTemplate redis;
    @Mock private UserMapper userMapper;
    @InjectMocks private LoginFailureGuard guard;
    private AutoCloseable mocks;
    @BeforeEach void setup() {
        mocks = MockitoAnnotations.openMocks(this);
        ReflectionTestUtils.setField(guard, "accountMaxFailures", 5);
        ReflectionTestUtils.setField(guard, "accountWindowSeconds", 60);
        ReflectionTestUtils.setField(guard, "accountCooldownSeconds", 60);
    }
    @AfterEach void cleanup() throws Exception { mocks.close(); }
    @ParameterizedTest
    @ValueSource(strings={"student01", "STUDENT01", "student01@example.test"})
    void aliasesOfExistingAccountShareTheSameCooldown(String login) {
        User user = new User(); user.setId(42L);
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(user);
        when(redis.getExpire("oj:login:lock:user:42")).thenReturn(30L);
        assertEquals(30L, guard.lockedSeconds(login));
        verify(redis).getExpire("oj:login:lock:user:42");
    }
    @Test void disabledGuardDoesNotTouchRedisOrDatabaseEvenAfterSuccess() {
        ReflectionTestUtils.setField(guard, "accountMaxFailures", 0);
        assertEquals(0L, guard.lockedSeconds("student01"));
        guard.recordFailure("student01");
        guard.clearFailures("student01");
        verifyNoInteractions(redis, userMapper);
    }
    @Test void counterExpiryAndCooldownAreRecordedInOneAtomicScript() {
        User user = new User(); user.setId(42L);
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(user);
        guard.recordFailure("student01");
        verify(redis).execute(any(DefaultRedisScript.class),
                eq(List.of("oj:login:fail:user:42", "oj:login:lock:user:42")),
                eq("5"), eq("60"), eq("60"));
        verify(redis, never()).opsForValue();
    }
    @Test void successDoesNotClearCooldownTriggeredByAnInFlightFailure() {
        User user = new User(); user.setId(42L);
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(user);
        guard.clearFailures("student01");
        verify(redis).delete("oj:login:fail:user:42");
        verify(redis, never()).delete("oj:login:lock:user:42");
    }
    @Test void unknownAccountStillUsesAFailureBucket() {
        when(redis.getExpire(anyString())).thenReturn(20L);
        assertEquals(20L, guard.lockedSeconds("unknown-login"));
        var key=ArgumentCaptor.forClass(String.class);
        verify(redis).getExpire(key.capture());
        assertTrue(key.getValue().startsWith("oj:login:lock:input:"));
        assertFalse(key.getValue().contains("unknown-login"));
    }
}
