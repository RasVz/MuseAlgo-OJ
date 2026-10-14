package top.everblue.oj.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import top.everblue.oj.api.ai.UserAiUsageQuery;
import top.everblue.oj.api.submission.UserSubmissionMetricsQuery;
import top.everblue.oj.system.entity.User;
import top.everblue.oj.system.service.UserService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminUserControllerTest {

    private UserService userService;
    private AdminUserController controller;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        when(userService.lockEnabledAdminIds()).thenReturn(List.of(1L, 2L));
        controller = new AdminUserController(userService,
                mock(UserSubmissionMetricsQuery.class), mock(UserAiUsageQuery.class),
                mock(org.springframework.security.crypto.password.PasswordEncoder.class));
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

    private static User admin(Long id) {
        User user = new User();
        user.setId(id);
        user.setRole((byte) 3);
        user.setIsBanned((byte) 0);
        return user;
    }
}
