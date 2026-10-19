package top.everblue.oj.judge.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import top.everblue.oj.api.community.FollowRelationQuery;
import top.everblue.oj.api.contest.ContestScheduleQuery;
import top.everblue.oj.api.contest.ContestSchedule;
import top.everblue.oj.api.problem.ProblemQuery;
import top.everblue.oj.api.user.UserQuery;
import top.everblue.oj.judge.application.SubmissionApplicationService;
import top.everblue.oj.judge.entity.Submission;
import top.everblue.oj.judge.mapper.SubmissionCodeMapper;
import top.everblue.oj.judge.service.SubmissionService;
import top.everblue.oj.system.entity.User;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubmissionControllerSecurityTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @SuppressWarnings("unchecked")
    void studentStatusFilterCannotExcludeRunningOiSubmissions() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Submission.class);
        User user = new User();
        user.setId(10L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        SubmissionService submissionService = mock(SubmissionService.class);
        ContestScheduleQuery contestScheduleQuery = mock(ContestScheduleQuery.class);
        when(contestScheduleQuery.findRunningOiContestIds()).thenReturn(Set.of(7L));
        when(contestScheduleQuery.findRunningOiVpAttemptIds()).thenReturn(Set.of());
        when(submissionService.page(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(new Page<Submission>(1, 15));
        SubmissionController controller = new SubmissionController(
                submissionService,
                mock(UserQuery.class),
                mock(ProblemQuery.class),
                contestScheduleQuery,
                mock(FollowRelationQuery.class),
                mock(SubmissionApplicationService.class),
                mock(SubmissionCodeMapper.class));

        controller.list(1, 15, null, (byte) 1, null, null, false, false);

        var wrapperCaptor = org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        org.mockito.Mockito.verify(submissionService).page(any(Page.class), wrapperCaptor.capture());
        String sql = wrapperCaptor.getValue().getCustomSqlSegment();
        assertTrue(sql.contains("status"));
        assertTrue(sql.contains("contest_id"));
        assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().containsValue(7L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void studentStatusFilterCannotExcludeRunningOiVpSubmissions() {
        login(10L);
        SubmissionService submissionService = mock(SubmissionService.class);
        ContestScheduleQuery contestScheduleQuery = mock(ContestScheduleQuery.class);
        when(contestScheduleQuery.findRunningOiContestIds()).thenReturn(Set.of());
        when(contestScheduleQuery.findRunningOiVpAttemptIds()).thenReturn(Set.of(9L));
        when(submissionService.page(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(new Page<Submission>(1, 15));
        SubmissionController controller = controller(submissionService, mock(UserQuery.class),
                mock(ProblemQuery.class), contestScheduleQuery);

        controller.list(1, 15, null, (byte) 1, null, null, false, false);

        var wrapperCaptor = org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        org.mockito.Mockito.verify(submissionService).page(any(Page.class), wrapperCaptor.capture());
        assertTrue(wrapperCaptor.getValue().getCustomSqlSegment().contains("vp_attempt_id IN"));
    }

    @Test
    void otherUserCannotViewSubmissionDuringRunningVp() {
        login(10L);
        SubmissionService submissionService = mock(SubmissionService.class);
        Submission submission = new Submission();
        submission.setId(5L);
        submission.setUserId(20L);
        submission.setProblemId(100L);
        submission.setContestId(1L);
        submission.setVpAttemptId(9L);
        when(submissionService.getById(5L)).thenReturn(submission);
        ContestScheduleQuery contestScheduleQuery = mock(ContestScheduleQuery.class);
        when(contestScheduleQuery.isVpAttemptRunning(9L)).thenReturn(true);
        when(contestScheduleQuery.findById(1L)).thenReturn(Optional.of(new ContestSchedule(
                1L, LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(1), "ACM", 1L)));
        ProblemQuery problemQuery = mock(ProblemQuery.class);
        when(problemQuery.findTitleById(100L)).thenReturn(Optional.of("A"));
        UserQuery userQuery = mock(UserQuery.class);
        when(userQuery.findDisplayInfo(20L)).thenReturn(Optional.empty());
        SubmissionController controller = new SubmissionController(
                submissionService, userQuery, problemQuery, contestScheduleQuery,
                mock(FollowRelationQuery.class), mock(SubmissionApplicationService.class),
                mock(SubmissionCodeMapper.class));

        var result = controller.detail(5L);

        assertTrue(result.getCode() == 403);
    }

    @Test
    void ownerOnlySeesSubmittedStateDuringRunningOiVp() {
        login(20L);
        SubmissionService submissionService = mock(SubmissionService.class);
        Submission submission = new Submission();
        submission.setId(5L);
        submission.setUserId(20L);
        submission.setProblemId(100L);
        submission.setContestId(1L);
        submission.setVpAttemptId(9L);
        submission.setStatus((byte) 1);
        submission.setScore(100);
        when(submissionService.getById(5L)).thenReturn(submission);
        ContestScheduleQuery contestScheduleQuery = mock(ContestScheduleQuery.class);
        when(contestScheduleQuery.findRunningOiVpAttemptIds()).thenReturn(Set.of(9L));
        when(contestScheduleQuery.findById(1L)).thenReturn(Optional.of(new ContestSchedule(
                1L, LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(1), "OI", 1L)));
        ProblemQuery problemQuery = mock(ProblemQuery.class);
        when(problemQuery.findTitleById(100L)).thenReturn(Optional.of("A"));
        UserQuery userQuery = mock(UserQuery.class);
        when(userQuery.findDisplayInfo(20L)).thenReturn(Optional.empty());
        SubmissionController controller = controller(
                submissionService, userQuery, problemQuery, contestScheduleQuery);

        var result = controller.detail(5L);

        assertTrue(result.getData().getStatus() == null);
        assertTrue(result.getData().getScore() == null);
        assertTrue("SUBMITTED".equals(result.getData().getJudgePhase()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listWithNonContestSubmissionDoesNotThrowWhenHiddenSetsAreImmutable() {
        login(10L);
        SubmissionService submissionService = mock(SubmissionService.class);
        Submission submission = new Submission();
        submission.setId(1L);
        submission.setUserId(10L);
        submission.setProblemId(100L);
        submission.setContestId(null);
        submission.setVpAttemptId(null);
        Page<Submission> page = new Page<>(1, 15);
        page.setRecords(List.of(submission));
        page.setTotal(1);
        when(submissionService.page(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);

        ContestScheduleQuery contestScheduleQuery = mock(ContestScheduleQuery.class);
        when(contestScheduleQuery.findRunningOiContestIds()).thenReturn(Set.of());
        when(contestScheduleQuery.findRunningOiVpAttemptIds()).thenReturn(Set.of());
        UserQuery userQuery = mock(UserQuery.class);
        when(userQuery.findDisplayInfoBatch(any())).thenReturn(Map.of());
        ProblemQuery problemQuery = mock(ProblemQuery.class);
        when(problemQuery.findTitleBatch(any())).thenReturn(Map.of());

        SubmissionController controller = controller(
                submissionService, userQuery, problemQuery, contestScheduleQuery);

        var result = controller.list(1, 15, null, null, null, null, false, false);

        assertEquals(200, result.getCode());
        assertEquals("未知题目", result.getData().getRecords().get(0).getProblemTitle());
    }

    @Test
    void detailWithNullVpAttemptDoesNotThrowWhenNoRunningOiVp() {
        login(10L);
        SubmissionService submissionService = mock(SubmissionService.class);
        Submission submission = new Submission();
        submission.setId(5L);
        submission.setUserId(10L);
        submission.setProblemId(100L);
        submission.setContestId(1L);
        submission.setVpAttemptId(null);
        submission.setStatus((byte) 1);
        when(submissionService.getById(5L)).thenReturn(submission);

        ContestScheduleQuery contestScheduleQuery = mock(ContestScheduleQuery.class);
        when(contestScheduleQuery.findById(1L)).thenReturn(Optional.of(new ContestSchedule(
                1L, LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(1), "ACM", 1L)));
        when(contestScheduleQuery.findRunningOiVpAttemptIds()).thenReturn(Set.of());
        ProblemQuery problemQuery = mock(ProblemQuery.class);
        when(problemQuery.findTitleById(100L)).thenReturn(Optional.of("A"));
        UserQuery userQuery = mock(UserQuery.class);
        when(userQuery.findDisplayInfo(10L)).thenReturn(Optional.empty());

        SubmissionController controller = controller(
                submissionService, userQuery, problemQuery, contestScheduleQuery);

        var result = controller.detail(5L);

        assertEquals(200, result.getCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void anonymousMineOnlyCannotBypassHiddenSubmissions() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Submission.class);
        SubmissionService service = mock(SubmissionService.class);
        when(service.page(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(new Page<Submission>(1, 15));
        UserQuery users = mock(UserQuery.class);
        when(users.findUserIdsWithSubmissionsHidden()).thenReturn(List.of(20L));
        var controller = controller(service, users, mock(ProblemQuery.class), mock(ContestScheduleQuery.class));
        controller.list(1, 15, null, null, null, null, true, false);
        var captor = org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        org.mockito.Mockito.verify(service).page(any(Page.class), captor.capture());
        assertTrue(captor.getValue().getCustomSqlSegment().contains("user_id NOT IN"));
    }

    @Test
    void detailPreservesNumericScore() {
        login(10L);
        var service = mock(SubmissionService.class);
        var submission = new Submission(); submission.setId(5L); submission.setUserId(10L); submission.setProblemId(100L);
        submission.setStatus((byte)1); submission.setScore(73);
        when(service.getById(5L)).thenReturn(submission);
        var controller = controller(service, mock(UserQuery.class), mock(ProblemQuery.class), mock(ContestScheduleQuery.class));
        assertEquals(73, controller.detail(5L).getData().getScore());
    }

    private static void login(Long userId) {
        User user = new User();
        user.setId(userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private static SubmissionController controller(SubmissionService submissionService,
                                                   UserQuery userQuery,
                                                   ProblemQuery problemQuery,
                                                   ContestScheduleQuery contestScheduleQuery) {
        return new SubmissionController(submissionService, userQuery, problemQuery,
                contestScheduleQuery, mock(FollowRelationQuery.class),
                mock(SubmissionApplicationService.class), mock(SubmissionCodeMapper.class));
    }
}
