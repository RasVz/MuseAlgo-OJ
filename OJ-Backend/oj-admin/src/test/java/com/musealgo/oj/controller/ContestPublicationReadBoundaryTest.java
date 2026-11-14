package com.musealgo.oj.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.musealgo.oj.api.submission.ContestVpQuery;
import com.musealgo.oj.api.problem.ProblemQuery;
import com.musealgo.oj.api.submission.ContestSubmissionQuery;
import com.musealgo.oj.api.submission.PageResult;
import com.musealgo.oj.api.user.UserQuery;
import com.musealgo.oj.common.exception.ForbiddenException;
import com.musealgo.oj.contest.application.ContestManagementApplicationService;
import com.musealgo.oj.contest.controller.ContestController;
import com.musealgo.oj.contest.controller.ContestRankController;
import com.musealgo.oj.contest.controller.ContestRankingSseController;
import com.musealgo.oj.contest.controller.ContestUserController;
import com.musealgo.oj.contest.entity.Contest;
import com.musealgo.oj.contest.entity.ProblemContest;
import com.musealgo.oj.contest.entity.VpAttempt;
import com.musealgo.oj.contest.mapper.*;
import com.musealgo.oj.contest.service.*;
import com.musealgo.oj.contest.service.impl.ContestUserServiceImpl;
import com.musealgo.oj.contest.service.impl.ProblemContestServiceImpl;
import com.musealgo.oj.contest.service.impl.VpAttemptServiceImpl;
import com.musealgo.oj.contest.sse.ContestRankSsePublisher;
import com.musealgo.oj.contest.sse.SseRegistry;
import com.musealgo.oj.system.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContestPublicationReadBoundaryTest {
    private final ContestService contests = mock(ContestService.class);
    private final ContestRankService ranks = mock(ContestRankService.class);
    private final SseRegistry streams = mock(SseRegistry.class);
    private final ContestSubmissionQuery submissions = mock(ContestSubmissionQuery.class);
    private final ContestRankController rankController = new ContestRankController(contests, ranks);
    private final ContestRankingSseController streamController = new ContestRankingSseController(streams, contests);
    private final ContestController controller = new ContestController(contests, null, null, null, null, null, submissions);

    @BeforeEach void prepareMetadata() {
        SecurityContextHolder.clearContext();
        for (Class<?> type : List.of(VpAttempt.class, ProblemContest.class)) {
            if (TableInfoHelper.getTableInfo(type) == null) {
                TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "publication-read"), type);
            }
        }
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void hiddenRankRowsAreDeniedToStudentAndForeignTeacher() {
        when(contests.getById(5L)).thenReturn(contest(0));
        for (String role : List.of("STUDENT", "TEACHER")) {
            login(9L, role);
            assertAll(
                    () -> assertThrows(ForbiddenException.class, () -> rankController.getRealtimeRankList(5L, 10)),
                    () -> assertThrows(ForbiddenException.class, () -> rankController.getMyRealtimeRank(5L)),
                    () -> assertThrows(ForbiddenException.class, () -> rankController.getFinalRankList(5L)),
                    () -> assertThrows(ForbiddenException.class, () -> rankController.getGlobalRankList(5L)));
        }
        verifyNoInteractions(ranks);
    }

    @Test void hiddenRankStreamsAreDeniedBeforeRegistration() {
        when(contests.getById(5L)).thenReturn(contest(0));
        login(9L, "STUDENT");
        assertAll(
                () -> assertThrows(ForbiddenException.class, () -> streamController.streamOfficial(5L)),
                () -> assertThrows(ForbiddenException.class, () -> streamController.streamGlobal(5L)));
        verifyNoInteractions(streams);
    }

    @Test void hiddenSubmissionPageIsDeniedBeforeFetchingRows() {
        when(contests.getById(5L)).thenReturn(contest(0));
        lenient().when(submissions.findPage(any())).thenReturn(new PageResult<>(1, 20, 0, List.of()));
        login(9L, "STUDENT");
        assertThrows(ForbiddenException.class,
                () -> controller.getContestSubmissionList(5L, 1, 20, false, null, null, null));
        verifyNoInteractions(submissions);
    }

    @Test void visibleQuestionFlagCannotExposeAnUnpublishedContest() {
        when(contests.getById(5L)).thenReturn(contest(0));
        ProblemContestMapper questions = mock(ProblemContestMapper.class);
        ProblemContestServiceImpl service = new ProblemContestServiceImpl(contests,
                mock(ContestManagementApplicationService.class));
        ReflectionTestUtils.setField(service, "baseMapper", questions);
        login(9L, "STUDENT");
        assertThrows(ForbiddenException.class, () -> service.selectContestProblemList(5L, 1, 9L));
        verifyNoInteractions(questions);
    }

    @Test void hiddenRegistrationIsDeniedBeforeSavingOrLookingUpRating() {
        ContestMapper contestMapper = mock(ContestMapper.class);
        ContestUserMapper registrations = mock(ContestUserMapper.class);
        UserQuery users = mock(UserQuery.class);
        ContestUserServiceImpl service = new ContestUserServiceImpl(contestMapper, users, mock(ContestEligibilityService.class));
        ReflectionTestUtils.setField(service, "baseMapper", registrations);
        when(contestMapper.selectByIdForUpdate(5L)).thenReturn(contest(0));
        login(9L, "STUDENT");
        assertThrows(ForbiddenException.class, () -> service.registerContest(5L, 9L));
        verifyNoInteractions(registrations, users);
    }

    @Test void registrationControllerReportsAForbiddenResultForHiddenContest() {
        ContestUserService registrations = mock(ContestUserService.class);
        when(registrations.registerContest(5L, 9L)).thenThrow(new ForbiddenException("无权查看未公开比赛"));
        login(9L, "STUDENT");
        ContestUserController registrationController = new ContestUserController(registrations, contests,
                mock(com.musealgo.oj.contest.application.ContestAnswerApplicationService.class));
        assertEquals(403, registrationController.register(5L).getCode());
    }

    @Test void hiddenVpStartAndCorrectionAreDeniedBeforeFetchingAttemptsOrQuestions() {
        when(contests.getByIdForUpdate(5L)).thenReturn(contest(0));
        when(contests.getById(5L)).thenReturn(contest(0));
        VpAttemptMapper attempts = mock(VpAttemptMapper.class);
        ProblemContestMapper questions = mock(ProblemContestMapper.class);
        VpAttempt running = new VpAttempt();
        running.setId(1L); running.setContestId(5L); running.setUserId(9L);
        running.setStatus(VpAttempt.STATUS_RUNNING);
        running.setStartTime(LocalDateTime.now().minusMinutes(2));
        running.setEndTime(LocalDateTime.now().plusMinutes(10));
        lenient().when(attempts.selectOne(any(Wrapper.class))).thenReturn(running);
        VpAttemptServiceImpl service = new VpAttemptServiceImpl(attempts, contests, mock(ContestRankMapper.class),
                questions, mock(ProblemQuery.class), mock(ContestVpQuery.class), mock(ContestRankSsePublisher.class));
        login(9L, "STUDENT");
        assertAll(
                () -> assertThrows(ForbiddenException.class, () -> service.start(5L, 9L)),
                () -> assertThrows(ForbiddenException.class, () -> service.correction(5L, 9L)));
        verifyNoInteractions(attempts, questions);
    }

    @Test void publishedRankStreamsAndSubmissionsStillWork() {
        when(contests.getById(5L)).thenReturn(contest(1));
        when(submissions.findPage(any())).thenReturn(new PageResult<>(1, 20, 0, List.of()));
        login(9L, "STUDENT");
        assertEquals(200, rankController.getGlobalRankList(5L).getCode());
        assertEquals(200, controller.getContestSubmissionList(5L, 1, 20, false, null, null, null).getCode());
        streamController.streamOfficial(5L).complete();
        verify(ranks).getGlobalRankList(5L);
        verify(streams).register(eq(5L), any(), any());
    }

    @Test void ownerAndAdminRetainHiddenRankAndSubmissionAccess() {
        when(contests.getById(5L)).thenReturn(contest(0));
        when(submissions.findPage(any())).thenReturn(new PageResult<>(1, 20, 0, List.of()));
        login(8L, "TEACHER");
        assertEquals(200, rankController.getFinalRankList(5L).getCode());
        assertEquals(200, controller.getContestSubmissionList(5L, 1, 20, false, null, null, null).getCode());
        login(99L, "ADMIN");
        assertEquals(200, rankController.getFinalRankList(5L).getCode());
    }

    private static Contest contest(int visible) {
        Contest contest = new Contest();
        contest.setId(5L); contest.setAuthorId(8L); contest.setVisible(visible);
        contest.setStartTime(LocalDateTime.now().minusHours(2));
        contest.setEndTime(LocalDateTime.now().minusHours(1));
        return contest;
    }

    private static void login(long id, String role) {
        User user = new User(); user.setId(id);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
