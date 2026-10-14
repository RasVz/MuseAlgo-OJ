package top.everblue.oj.system.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.utils.JwtUtils;
import top.everblue.oj.system.service.JwtBlacklistService;
import top.everblue.oj.system.service.UserService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthController 注销/刷新黑名单单测：logout 幂等与双 token 拉黑、refresh 拦截已注销。
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerLogoutTest {

    @Mock UserService userService;
    @Mock AuthenticationManager authenticationManager;
    @Mock JwtUtils jwtUtils;
    @Mock JwtBlacklistService jwtBlacklistService;
    @InjectMocks AuthController authController;

    private static final String ACCESS = "access-token";
    private static final String REFRESH = "refresh-token";

    @Test
    void logout_拉黑access与refresh() {
        when(jwtUtils.extractJti(ACCESS)).thenReturn("jti-access");
        when(jwtUtils.extractJti(REFRESH)).thenReturn("jti-refresh");
        when(jwtUtils.getRemainingTtl(ACCESS)).thenReturn(3600_000L);
        when(jwtUtils.getRemainingTtl(REFRESH)).thenReturn(7200_000L);

        Result<String> result = authController.logout("Bearer " + ACCESS, Map.of("refreshToken", REFRESH));

        assertEquals(200, result.getCode());
        verify(jwtBlacklistService).revoke("jti-access", 3600_000L);
        verify(jwtBlacklistService).revoke("jti-refresh", 7200_000L);
    }

    @Test
    void logout_无token幂等返回成功() {
        Result<String> result = authController.logout(null, Map.of());

        assertEquals(200, result.getCode());
        verify(jwtBlacklistService, never()).revoke(anyString(), anyLong());
    }

    @Test
    void logout_无效token静默跳过仍成功() {
        when(jwtUtils.extractJti("bad-token")).thenThrow(new RuntimeException("expired"));

        Result<String> result = authController.logout("Bearer bad-token", Map.of());

        assertEquals(200, result.getCode());
        verify(jwtBlacklistService, never()).revoke(anyString(), anyLong());
    }

    @Test
    void refresh_refreshToken已注销返回401() {
        when(jwtUtils.validateToken(REFRESH, "stu")).thenReturn(true);
        when(jwtUtils.extractUsername(REFRESH)).thenReturn("stu");
        when(jwtUtils.extractJti(REFRESH)).thenReturn("jti-refresh");
        when(jwtBlacklistService.isRevoked("jti-refresh")).thenReturn(true);

        Result<String> result = authController.refreshToken(Map.of("refreshToken", REFRESH));

        assertEquals(401, result.getCode());
    }

    @Test
    void refresh_未注销正常刷新() {
        when(jwtUtils.validateToken(REFRESH, "stu")).thenReturn(true);
        when(jwtUtils.extractUsername(REFRESH)).thenReturn("stu");
        when(jwtUtils.extractJti(REFRESH)).thenReturn("jti-refresh");
        when(jwtBlacklistService.isRevoked("jti-refresh")).thenReturn(false);
        when(jwtUtils.generateAccessToken("stu")).thenReturn("new-access");

        Result<String> result = authController.refreshToken(Map.of("refreshToken", REFRESH));

        assertEquals(200, result.getCode());
        assertEquals("new-access", result.getData());
    }
}
