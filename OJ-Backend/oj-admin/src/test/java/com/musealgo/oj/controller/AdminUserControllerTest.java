package com.musealgo.oj.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import com.musealgo.oj.api.ai.UserAiUsageQuery;
import com.musealgo.oj.api.submission.UserSubmissionMetricsQuery;
import com.musealgo.oj.system.entity.User;
import com.musealgo.oj.system.service.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminUserControllerTest {

    private UserService userService;
    private AdminUserController controller;
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        when(userService.lockEnabledAdminIds()).thenReturn(List.of(1L, 2L));
        passwordEncoder = mock(PasswordEncoder.class);
        controller = new AdminUserController(userService,
                mock(UserSubmissionMetricsQuery.class), mock(UserAiUsageQuery.class), passwordEncoder);
        User current = new User();
        current.setId(1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(current, null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void cannotDowngradeOwnAdminRole() {
        when(userService.getById(1L)).thenReturn(admin(1L));
        AdminUserController.RoleRequest request = new AdminUserController.RoleRequest();
        request.setRole((byte) 2);

        var result = controller.updateRole(1L, request);

        assertEquals("不能降低自己的管理员角色", result.getMessage());
        verify(userService, never()).updateById(any());
    }

    @Test
    void cannotBanOwnAccount() {
        when(userService.getById(1L)).thenReturn(admin(1L));
        AdminUserController.BanRequest request = new AdminUserController.BanRequest();
        request.setBanned(true);

        var result = controller.setBanned(1L, request);

        assertEquals("不能禁用自己的账号", result.getMessage());
        verify(userService, never()).updateById(any());
    }

    @Test
    void cannotDowngradeLastEnabledAdmin() {
        when(userService.getById(2L)).thenReturn(admin(2L));
        when(userService.lockEnabledAdminIds()).thenReturn(List.of(2L));
        AdminUserController.RoleRequest request = new AdminUserController.RoleRequest();
        request.setRole((byte) 2);

        var result = controller.updateRole(2L, request);

        assertEquals("系统必须保留至少一个管理员", result.getMessage());
        verify(userService, never()).updateById(any());
    }

    @Test
    void cannotBanLastEnabledAdmin() {
        when(userService.getById(2L)).thenReturn(admin(2L));
        when(userService.lockEnabledAdminIds()).thenReturn(List.of(2L));
        AdminUserController.BanRequest request = new AdminUserController.BanRequest();
        request.setBanned(true);

        var result = controller.setBanned(2L, request);

        assertEquals("不能禁用最后一个管理员", result.getMessage());
        verify(userService, never()).updateById(any());
    }

    @Test
    void adminPasswordResetAtomicallyInvalidatesPriorSessions() {
        when(userService.getById(2L)).thenReturn(teacher(2L));
        when(passwordEncoder.encode("new-password")).thenReturn("encoded-password");
        when(userService.updatePassword(2L, "encoded-password")).thenReturn(true);
        AdminUserController.ResetPasswordRequest request = new AdminUserController.ResetPasswordRequest();
        request.setNewPassword("new-password");

        var result = controller.resetPassword(2L, request);

        assertEquals(200, result.getCode());
        verify(userService).updatePassword(2L, "encoded-password");
        verify(userService, never()).updateById(any());
    }

    // ===== SEC-01：规范化 RBAC 模式下的关联同步 =====

    @Test
    void failedRoleWriteCannotReportSuccessOrSyncNormalizedRoles() {
        setNormalized(true);
        when(userService.getById(2L)).thenReturn(teacher(2L));
        when(userService.updateById(any(User.class))).thenReturn(false);
        AdminUserController.RoleRequest request = new AdminUserController.RoleRequest(); request.setRole((byte)3);
        assertThrows(IllegalStateException.class,()->controller.updateRole(2L,request));
        verify(userService,never()).syncNormalizedRole(any(),any());
    }

    @Test
    void failedBanWriteCannotReportSuccess() {
        when(userService.getById(2L)).thenReturn(teacher(2L));
        when(userService.updateById(any(User.class))).thenReturn(false);
        AdminUserController.BanRequest request = new AdminUserController.BanRequest(); request.setBanned(true);
        assertThrows(IllegalStateException.class,()->controller.setBanned(2L,request));
    }

    @Test
    void legacyModeRoleChangeDoesNotTouchNormalizedAssociations() {
        setNormalized(false);
        when(userService.getById(2L)).thenReturn(admin(2L));
        when(userService.updateById(any(User.class))).thenReturn(true);
        AdminUserController.RoleRequest request = new AdminUserController.RoleRequest();
        request.setRole((byte) 2);

        controller.updateRole(2L, request);

        verify(userService).updateById(any());
        // legacy 模式授权只看 user.role，不应写 sys_user_role（保持历史库零改动）
        verify(userService, never()).syncNormalizedRole(any(), any());
    }

    @Test
    void normalizedModeRoleChangeSyncsAssociation() {
        setNormalized(true);
        when(userService.getById(2L)).thenReturn(teacher(2L));
        when(userService.updateById(any(User.class))).thenReturn(true);
        when(userService.syncNormalizedRole(2L, (byte) 3)).thenReturn(true);
        AdminUserController.RoleRequest request = new AdminUserController.RoleRequest();
        request.setRole((byte) 3);

        controller.updateRole(2L, request);

        verify(userService).updateById(any());
        verify(userService).syncNormalizedRole(2L, (byte) 3);
    }

    @Test
    void normalizedModeRoleChangeFailsClosedWhenAssociationCannotBeSynced() {
        setNormalized(true);
        when(userService.getById(2L)).thenReturn(teacher(2L));
        when(userService.updateById(any(User.class))).thenReturn(true);
        when(userService.syncNormalizedRole(2L, (byte) 3)).thenReturn(false);
        AdminUserController.RoleRequest request = new AdminUserController.RoleRequest();
        request.setRole((byte) 3);

        // 关联同步失败必须抛异常（事务回滚 user.role），不能留下“user.role 已改但关联仍旧”的不一致
        assertThrows(IllegalStateException.class, () -> controller.updateRole(2L, request));
    }

    @Test
    void legacyModeCreateAccountDoesNotTouchNormalizedAssociations() {
        setNormalized(false);
        when(userService.registerCheck(any())).thenReturn(0);
        when(userService.save(any())).thenReturn(true);
        AdminUserController.CreateAccountRequest request = createRequest("new_student", 1);

        var result = controller.createAccount(request);

        assertEquals(200, result.getCode());
        assertEquals("账号创建成功", result.getData());
        verify(userService).save(any());
        verify(userService, never()).syncNormalizedRole(any(), any());
    }

    @Test
    void normalizedModeCreateAccountSyncsAssociation() {
        setNormalized(true);
        when(userService.registerCheck(any())).thenReturn(0);
        when(userService.save(any())).thenAnswer(inv -> {
            ((User) inv.getArgument(0)).setId(99L);
            return true;
        });
        when(userService.syncNormalizedRole(99L, (byte) 2)).thenReturn(true);
        AdminUserController.CreateAccountRequest request = createRequest("new_teacher", 2);

        var result = controller.createAccount(request);

        assertEquals(200, result.getCode());
        assertEquals("账号创建成功", result.getData());
        verify(userService).syncNormalizedRole(99L, (byte) 2);
    }

    private void setNormalized(boolean enabled) {
        ReflectionTestUtils.setField(controller, "normalizedRbacEnabled", enabled);
    }

    private static AdminUserController.CreateAccountRequest createRequest(String username, int role) {
        AdminUserController.CreateAccountRequest request = new AdminUserController.CreateAccountRequest();
        request.setUsername(username);
        request.setPassword("secret123");
        request.setNickname("nick");
        request.setRole(role);
        return request;
    }

    private static User admin(Long id) {
        User user = new User();
        user.setId(id);
        user.setRole((byte) 3);
        user.setIsBanned((byte) 0);
        return user;
    }

    private static User teacher(Long id) {
        User user = new User();
        user.setId(id);
        user.setRole((byte) 2);
        user.setIsBanned((byte) 0);
        return user;
    }
}
