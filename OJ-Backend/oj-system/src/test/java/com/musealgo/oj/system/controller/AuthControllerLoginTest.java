package com.musealgo.oj.system.controller;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.musealgo.oj.common.exception.GlobalExceptionHandler;
import com.musealgo.oj.common.utils.JwtUtils;
import com.musealgo.oj.system.service.JwtBlacklistService;
import com.musealgo.oj.system.service.LoginFailureGuard;
import com.musealgo.oj.system.service.UserService;

import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验登录认证异常的公开响应保持一致，不向调用方泄露账号状态。
 * 使用模拟认证器验证控制器映射；真实用户状态由独立 HTTP 验收覆盖。
 */
class AuthControllerLoginTest {
    private static Stream<AuthenticationException> authenticationFailures() {
        return Stream.of(new BadCredentialsException("wrong password"),
                new UsernameNotFoundException("unknown user"),
                new BadCredentialsException("empty password"),
                new DisabledException("banned user"),
                new LockedException("locked user"));
    }

    @ParameterizedTest
    @MethodSource("authenticationFailures")
    void loginAuthenticationFailuresUseTheSamePublicResponse(AuthenticationException failure) throws Exception {
        AuthenticationManager manager = mock(AuthenticationManager.class);
        when(manager.authenticate(any())).thenThrow(failure);
        AuthController controller = new AuthController(mock(UserService.class), manager,
                mock(JwtUtils.class), mock(JwtBlacklistService.class), mock(LoginFailureGuard.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"example\",\"password\":\"example\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }
    @org.junit.jupiter.api.Test
    void authenticationInfrastructureFailureIsNotInvalidCredentials() throws Exception {
        AuthenticationManager manager = mock(AuthenticationManager.class);
        when(manager.authenticate(any())).thenThrow(new org.springframework.security.authentication.InternalAuthenticationServiceException("SQL private detail"));
        LoginFailureGuard guard = mock(LoginFailureGuard.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AuthController(mock(UserService.class), manager,
                mock(JwtUtils.class), mock(JwtBlacklistService.class), guard))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"student\",\"password\":\"password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.errorCode").value("AUTH_DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("暂时无法验证登录状态，请稍后重试"));
        org.mockito.Mockito.verify(guard, org.mockito.Mockito.never()).recordFailure(any());
    }

    @org.junit.jupiter.api.Test
    void wrappedDisabledAccountIsReportedAsInvalidCredentials() throws Exception {
        AuthenticationManager manager = mock(AuthenticationManager.class);
        // 生产路径：禁用异常在 retrieveUser 阶段抛出，被包装成 InternalAuthenticationServiceException。
        when(manager.authenticate(any())).thenThrow(new org.springframework.security.authentication.InternalAuthenticationServiceException(
                "wrapped", new DisabledException("banned user")));
        var mvc = MockMvcBuilders.standaloneSetup(new AuthController(mock(UserService.class), manager,
                mock(JwtUtils.class), mock(JwtBlacklistService.class), mock(LoginFailureGuard.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"student\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @org.junit.jupiter.api.Test
    void duplicateRegistrationHasStableConflictReason() throws Exception {
        UserService users = mock(UserService.class);
        when(users.registerCheck(any())).thenReturn(1);
        var mvc = MockMvcBuilders.standaloneSetup(new AuthController(users, mock(AuthenticationManager.class),
                mock(JwtUtils.class), mock(JwtBlacklistService.class), mock(LoginFailureGuard.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"student\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.errorCode").value("AUTH_USERNAME_TAKEN"));
        org.mockito.Mockito.verify(users, org.mockito.Mockito.never()).register(any());
    }

    @org.junit.jupiter.api.Test
    void refreshDependencyFailurePreservesUnavailableReason() throws Exception {
        JwtUtils jwt = mock(JwtUtils.class);
        when(jwt.extractUsername("valid-refresh")).thenReturn("student");
        when(jwt.validateRefreshToken("valid-refresh", "student")).thenReturn(true);
        when(jwt.extractJti("valid-refresh")).thenReturn("test-jti");
        JwtBlacklistService blacklist = mock(JwtBlacklistService.class);
        when(blacklist.isRevoked("test-jti")).thenThrow(new com.musealgo.oj.common.exception.DependencyUnavailableException(
                com.musealgo.oj.common.exception.CommonErrorCode.AUTH_DEPENDENCY_UNAVAILABLE, new RuntimeException("redis private detail")));
        var mvc = MockMvcBuilders.standaloneSetup(new AuthController(mock(UserService.class), mock(AuthenticationManager.class),
                jwt, blacklist, mock(LoginFailureGuard.class))).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"valid-refresh\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.errorCode").value("AUTH_DEPENDENCY_UNAVAILABLE"));
    }
}
