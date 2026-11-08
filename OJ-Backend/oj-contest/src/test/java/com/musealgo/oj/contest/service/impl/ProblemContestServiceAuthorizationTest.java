package com.musealgo.oj.contest.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import com.musealgo.oj.common.exception.ForbiddenException;
import com.musealgo.oj.common.security.AuthenticatedUser;
import com.musealgo.oj.contest.application.ContestManagementApplicationService;
import com.musealgo.oj.contest.entity.Contest;
import com.musealgo.oj.contest.entity.ProblemContest;
import com.musealgo.oj.contest.entity.vo.ContestProblemVO;
import com.musealgo.oj.contest.mapper.ProblemContestMapper;
import com.musealgo.oj.contest.service.ContestService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProblemContestServiceAuthorizationTest {

    @Mock private ProblemContestMapper problemContestMapper;
    @Mock private ContestService contestService;
    @Mock private ContestManagementApplicationService contestManagementApplicationService;
    @InjectMocks private ProblemContestServiceImpl service;

    @BeforeEach
    void setUpMapper() {
        ReflectionTestUtils.setField(service, "baseMapper", problemContestMapper);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void studentCannotAttachQuestionToSomeoneElsesContest() {
        login(41L, "ROLE_STUDENT");
        ProblemContest relation = new ProblemContest();
        relation.setContestId(5L);
        relation.setProblemId(90L);

        assertThrows(ForbiddenException.class, () -> service.addProblemToContest(relation));

        verify(problemContestMapper, never()).insert(any(ProblemContest.class));
    }

    @Test
    void teacherCannotRemoveAnotherAuthorsContestQuestion() {
        login(41L, "ROLE_TEACHER");
        when(contestManagementApplicationService.requireManagedContest(5L))
                .thenThrow(new ForbiddenException("no access"));
        lenient().when(problemContestMapper.selectById(31L)).thenReturn(relation(31L, 5L));

        assertThrows(ForbiddenException.class, () -> service.removeFromContest(31L));

        verify(problemContestMapper, never()).logicalDeleteById(anyLong(), any());
    }

    @Test
    void ownerCannotMoveTheirRelationIntoAnotherAuthorsContest() {
        login(8L, "ROLE_TEACHER");
        lenient().when(contestManagementApplicationService.requireManagedContest(5L))
                .thenReturn(contest(5L, 8L));
        when(contestManagementApplicationService.requireManagedContest(6L))
                .thenThrow(new ForbiddenException("no access"));
        lenient().when(problemContestMapper.selectById(31L)).thenReturn(relation(31L, 5L));
        ProblemContest update = relation(31L, 6L);
        update.setProblemOrder(2);

        assertThrows(ForbiddenException.class, () -> service.updateProblemConfig(update));

        verify(problemContestMapper, never()).updateById(any(ProblemContest.class));
    }

    @Test
    void teacherCanConfigureTheirOwnContest() {
        login(8L, "ROLE_TEACHER");
        when(contestManagementApplicationService.requireManagedContest(5L)).thenReturn(contest(5L, 8L));
        when(problemContestMapper.selectById(31L)).thenReturn(relation(31L, 5L));
        when(problemContestMapper.updateVisible(31L, 0)).thenReturn(1);

        assertTrue(service.toggleVisible(31L, 0));

        verify(problemContestMapper).updateVisible(31L, 0);
    }

    @Test
    void studentCannotRequestHiddenContestProblems() {
        login(41L, "ROLE_STUDENT");

        assertThrows(ForbiddenException.class,
                () -> service.selectContestProblemList(5L, 0, 41L));

        verify(problemContestMapper, never()).selectContestProblemList(5L, 0, 41L);
    }

    @Test
    void teacherCannotRequestAllContestProblemsForAnotherAuthor() {
        login(41L, "ROLE_TEACHER");
        when(contestManagementApplicationService.requireManagedContest(5L))
                .thenThrow(new ForbiddenException("no access"));

        assertThrows(ForbiddenException.class,
                () -> service.selectContestProblemList(5L, null, 41L));

        verify(problemContestMapper, never()).selectContestProblemList(5L, null, 41L);
    }

    @Test
    void ownerCanReadHiddenContestProblems() {
        login(8L, "ROLE_TEACHER");
        when(contestManagementApplicationService.requireManagedContest(5L)).thenReturn(contest(5L, 8L));
        when(problemContestMapper.selectContestProblemList(5L, 0, 8L)).thenReturn(List.of());

        assertTrue(service.selectContestProblemList(5L, 0, 8L).isEmpty());

        verify(contestManagementApplicationService).requireManagedContest(5L);
        verify(problemContestMapper).selectContestProblemList(5L, 0, 8L);
    }

    @Test
    void studentCanStillReadVisibleContestProblems() {
        login(41L, "ROLE_STUDENT");
        when(contestService.getById(5L)).thenReturn(contest(5L, 8L));
        when(problemContestMapper.selectContestProblemList(5L, 1, 41L)).thenReturn(List.of());

        assertTrue(service.selectContestProblemList(5L, 1, 41L).isEmpty());

        verify(problemContestMapper).selectContestProblemList(5L, 1, 41L);
    }

    private static Contest contest(Long id, Long authorId) {
        Contest contest = new Contest();
        contest.setId(id);
        contest.setAuthorId(authorId);
        contest.setVisible(1);
        return contest;
    }

    private static ProblemContest relation(Long id, Long contestId) {
        ProblemContest relation = new ProblemContest();
        relation.setId(id);
        relation.setContestId(contestId);
        relation.setProblemId(90L);
        relation.setIsDeleted(0);
        return relation;
    }

    private static void login(Long userId, String role) {
        AuthenticatedUser principal = () -> userId;
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority(role))));
    }
    @Test void failedSingleInsertDoesNotReturnNullAsASuccessfulId() {
        login(8L, "ROLE_TEACHER");
        when(contestManagementApplicationService.requireManagedContest(5L)).thenReturn(contest(5L, 8L));
        assertThrows(IllegalStateException.class, () -> service.addProblemToContest(relation(null, 5L)));
    }
    @Test void failedBatchInsertDoesNotReportProcessedSuccessfully() {
        login(8L, "ROLE_TEACHER");
        when(contestManagementApplicationService.requireManagedContest(5L)).thenReturn(contest(5L, 8L));
        var target = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(false).when(target).saveBatch(any(java.util.Collection.class));
        assertThrows(IllegalStateException.class, () -> target.batchAddProblems(5L, List.of(90L)));
    }

}
