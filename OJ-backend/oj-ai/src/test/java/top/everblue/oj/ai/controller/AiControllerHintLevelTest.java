package top.everblue.oj.ai.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import top.everblue.oj.ai.AiChatSessionService;
import top.everblue.oj.ai.AiService;
import top.everblue.oj.ai.quota.AiQuotaService;
import top.everblue.oj.ai.usage.AiUsageRecorder;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.exception.ForbiddenException;
import top.everblue.oj.common.security.AuthenticatedUser;

import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 学生端点 GET /ai/hint/level：返回当前用户对某题已解锁级别，未解锁返回 1，未登录 403。
 */
class AiControllerHintLevelTest {

    private AiChatSessionService chatSessionService;
    private AiController controller;

    /** 已认证主体：实现 AuthenticatedUser 标记接口 */
    private record TestUser(Long id) implements AuthenticatedUser {
        @Override
        public Long getId() {
            return id;
        }
    }

    @BeforeEach
    void setUp() {
        chatSessionService = mock(AiChatSessionService.class);
        controller = new AiController(
                mock(AiService.class),
                mock(AiQuotaService.class),
                mock(AiUsageRecorder.class),
                chatSessionService,
                mock(ExecutorService.class));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new TestUser(userId), null,
                        List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }

    @Test
    void returnsUnlockedLevelForCurrentUser() {
        loginAs(1L);
        when(chatSessionService.getHintLevel(1L, 10L)).thenReturn(3);

        Result<Integer> result = controller.hintLevel(10L);

        assertEquals(200, result.getCode());
        assertEquals(3, result.getData());
    }

    @Test
    void returnsMinLevelWhenNotUnlocked() {
        loginAs(1L);
        when(chatSessionService.getHintLevel(1L, 10L)).thenReturn(1);

        assertEquals(1, controller.hintLevel(10L).getData());
    }

    @Test
    void rejectsAnonymousUser() {
        assertThrows(ForbiddenException.class, () -> controller.hintLevel(10L));
    }
}
