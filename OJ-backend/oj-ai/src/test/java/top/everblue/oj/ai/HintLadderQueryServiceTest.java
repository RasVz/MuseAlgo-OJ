package top.everblue.oj.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import top.everblue.oj.ai.dto.HintLevelVo;
import top.everblue.oj.api.teaching.ClassQuery;
import top.everblue.oj.api.user.UserDisplayInfo;
import top.everblue.oj.api.user.UserQuery;
import top.everblue.oj.common.exception.ForbiddenException;
import top.everblue.oj.common.security.AuthenticatedUser;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 教师端批量聚合：班主任/管理员权限、Redis ∪ 轨迹表合并、username 投射、排序。
 */
@ExtendWith(MockitoExtension.class)
class HintLadderQueryServiceTest {

    @Mock
    private ClassQuery classQuery;
    @Mock
    private UserQuery userQuery;
    @Mock
    private AiChatSessionService chatSessionService;

    private HintLadderQueryService service;

    /** 已认证主体：测试用，实现 AuthenticatedUser 标记接口 */
    private record TestUser(Long id) implements AuthenticatedUser {
        @Override
        public Long getId() {
            return id;
        }
    }

    @BeforeEach
    void setUp() {
        service = new HintLadderQueryService(classQuery, userQuery, chatSessionService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(Long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new TestUser(userId), null, List.of(new SimpleGrantedAuthority(role))));
    }

    @Test
    void teacherOfClassGetsMergedLevelsWithUsernames() {
        loginAs(99L, "ROLE_TEACHER");
        when(classQuery.findById(5L))
                .thenReturn(java.util.Optional.of(new ClassQuery.ClassInfo(5L, "一班", 99L)));
        when(classQuery.findStudentIds(5L)).thenReturn(List.of(1L, 2L));
        when(chatSessionService.batchHintLevels(10L, List.of(1L, 2L)))
                .thenReturn(Map.of(1L, 4, 2L, 1));
        when(userQuery.findDisplayInfoBatch(List.of(1L, 2L)))
                .thenReturn(Map.of(1L, new UserDisplayInfo(1L, "alice", null, null, null, null, null),
                        2L, new UserDisplayInfo(2L, "bob", null, null, null, null, null)));

        List<HintLevelVo> result = service.classHintLevels(5L, 10L);

        assertEquals(2, result.size());
        // 按级别降序：alice(L4) 在前
        HintLevelVo first = result.get(0);
        assertEquals(1L, first.userId());
        assertEquals("alice", first.username());
        assertEquals(4, first.level());
        HintLevelVo second = result.get(1);
        assertEquals(2L, second.userId());
        assertEquals("bob", second.username());
        assertEquals(1, second.level());
    }

    @Test
    void adminCanViewAnyClass() {
        loginAs(1L, "ROLE_ADMIN");
        when(classQuery.findById(5L))
                .thenReturn(java.util.Optional.of(new ClassQuery.ClassInfo(5L, "一班", 99L)));
        when(classQuery.findStudentIds(5L)).thenReturn(List.of(2L));
        when(chatSessionService.batchHintLevels(10L, List.of(2L))).thenReturn(Map.of(2L, 3));
        when(userQuery.findDisplayInfoBatch(List.of(2L))).thenReturn(Map.of());

        List<HintLevelVo> result = service.classHintLevels(5L, 10L);

        assertEquals(1, result.size());
        assertEquals(3, result.get(0).level());
        assertEquals("用户2", result.get(0).username(), "用户信息缺失时回退为 userId 占位");
    }

    @Test
    void nonOwningTeacherIsForbidden() {
        loginAs(100L, "ROLE_TEACHER");
        when(classQuery.findById(5L))
                .thenReturn(java.util.Optional.of(new ClassQuery.ClassInfo(5L, "一班", 99L)));

        assertThrows(ForbiddenException.class, () -> service.classHintLevels(5L, 10L));
    }

    @Test
    void missingClassIsForbidden() {
        loginAs(1L, "ROLE_ADMIN");
        when(classQuery.findById(999L)).thenReturn(java.util.Optional.empty());

        assertThrows(ForbiddenException.class, () -> service.classHintLevels(999L, 10L));
    }

    @Test
    void unauthenticatedIsForbidden() {
        when(classQuery.findById(5L))
                .thenReturn(java.util.Optional.of(new ClassQuery.ClassInfo(5L, "一班", 99L)));
        assertThrows(ForbiddenException.class, () -> service.classHintLevels(5L, 10L));
        assertTrue(SecurityContextHolder.getContext().getAuthentication() == null
                || "anonymousUser".equals(SecurityContextHolder.getContext().getAuthentication().getName()));
    }
}
