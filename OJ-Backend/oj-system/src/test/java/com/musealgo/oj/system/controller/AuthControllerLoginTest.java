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
}
