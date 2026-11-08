package com.musealgo.oj.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.musealgo.oj.common.exception.ForbiddenException;
import com.musealgo.oj.contest.controller.ContestController;
import com.musealgo.oj.contest.entity.Contest;
import com.musealgo.oj.contest.service.ContestService;
import com.musealgo.oj.system.entity.User;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContestPublicationAccessTest {
    private final ContestService contests = mock(ContestService.class);
    private final ContestController controller = new ContestController(contests, null, null, null, null, null, null);

    @BeforeEach void prepare() {
        SecurityContextHolder.clearContext();
        if (TableInfoHelper.getTableInfo(Contest.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "contest-publication"), Contest.class);
        }
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void anonymousDefaultListIsRestrictedToPublishedContests() {
        when(contests.page(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            Wrapper<Contest> filter = invocation.getArgument(1);
            assertTrue(filter.getSqlSegment().contains("visible ="));
            assertEquals(List.of(1), List.copyOf(((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) filter)
                    .getParamNameValuePairs().values()));
            return invocation.getArgument(0);
        });
        controller.getList(1, 10, null, null);
    }

    @Test void anonymousCallerCannotRequestAnUnpublishedList() {
        lenient().when(contests.page(any(Page.class), any(Wrapper.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        assertEquals(403, controller.getList(1, 10, null, 0).getCode());
        verifyNoInteractions(contests);
    }

    @Test void teacherManagementListIncludesPublicAndOnlyTheirOwnUnpublishedRows() {
        login(8L, "TEACHER");
        when(contests.page(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            var filter = (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) invocation.getArgument(1);
            assertTrue(filter.getSqlSegment().contains(" OR "));
            assertTrue(filter.getSqlSegment().contains("author_id ="));
            assertTrue(filter.getParamNameValuePairs().containsValue(8L));
            assertTrue(filter.getParamNameValuePairs().containsValue(1));
            return invocation.getArgument(0);
        });
        controller.getList(1, 10, null, null);
    }

    @Test void hiddenDetailIsDeniedToAnonymousStudentAndForeignTeacher() {
        Contest draft = contest(0);
        when(contests.getById(5L)).thenReturn(draft);
        assertThrows(ForbiddenException.class, () -> controller.getDetail(5L));
        login(9L, "STUDENT");
        assertThrows(ForbiddenException.class, () -> controller.getDetail(5L));
        login(9L, "TEACHER");
        assertThrows(ForbiddenException.class, () -> controller.getDetail(5L));
    }

    @Test void ownerAndAdministratorCanStillReadDraftDetails() {
        when(contests.getById(5L)).thenReturn(contest(0));
        login(8L, "TEACHER");
        assertEquals(200, controller.getDetail(5L).getCode());
        login(99L, "ADMIN");
        assertEquals(200, controller.getDetail(5L).getCode());
    }

    @Test void publishedDetailRemainsPublic() {
        when(contests.getById(5L)).thenReturn(contest(1));
        assertEquals(200, controller.getDetail(5L).getCode());
    }

    private static Contest contest(int visible) {
        Contest contest = new Contest();
        contest.setId(5L);
        contest.setAuthorId(8L);
        contest.setVisible(visible);
        return contest;
    }

    private static void login(long id, String role) {
        User user = new User();
        user.setId(id);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
