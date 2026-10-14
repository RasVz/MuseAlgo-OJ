package top.everblue.oj.contest.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import top.everblue.oj.api.problem.ProblemQuery;
import top.everblue.oj.api.submission.ContestSubmission;
import top.everblue.oj.api.submission.ContestSubmissionQuery;
import top.everblue.oj.api.submission.PageResult;
import top.everblue.oj.api.user.UserQuery;
import top.everblue.oj.contest.application.ContestManagementApplicationService;
import top.everblue.oj.contest.entity.Contest;
import top.everblue.oj.contest.mapper.ProblemContestMapper;
import top.everblue.oj.contest.service.ContestRankService;
import top.everblue.oj.contest.service.ContestService;
import top.everblue.oj.contest.service.ContestUserService;
import top.everblue.oj.contest.service.ProblemContestService;
import top.everblue.oj.system.entity.User;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestControllerSecurityTest {

    @Mock ContestService contestService;
    @Mock ContestUserService contestUserService;
    @Mock ProblemContestService problemContestService;
    @Mock UserQuery userQuery;
    @Mock ProblemQuery problemQuery;
    @Mock ProblemContestMapper problemContestMapper;
    @Mock ContestRankService contestRankService;
    @Mock ContestManagementApplicationService contestManagementApplicationService;
    @Mock ContestSubmissionQuery contestSubmissionQuery;
    @InjectMocks ContestController controller;
    @InjectMocks ContestRankController rankController;

    @BeforeEach
    void loginAsStudent() {
        User user = new User();
        user.setId(10L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void oiContestInProgressOnlyShowsSubmittedState() {
        Contest contest = runningOiContest();
        when(contestService.getById(1L)).thenReturn(contest);
        when(contestSubmissionQuery.findPage(any())).thenReturn(new PageResult<>(1, 20, 1,
                List.of(new ContestSubmission(5L, 1L, null, 10L, 100L, (byte) 1,
                        "cpp", LocalDateTime.now(), 100))));
        when(userQuery.findDisplayInfoBatch(any())).thenReturn(Map.of());
        when(problemQuery.findTitleBatch(any())).thenReturn(Map.of(100L, "A"));

        var result = controller.getContestSubmissionList(1L, 1, 20, true,
                null, null, 1);

        var record = result.getData().getRecords().get(0);
        assertNull(record.getStatus());
        assertEquals("SUBMITTED", record.getJudgePhase());
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(
                top.everblue.oj.api.submission.ContestSubmissionPageRequest.class);
        verify(contestSubmissionQuery).findPage(requestCaptor.capture());
        assertNull(requestCaptor.getValue().status());
    }

    @Test
    void oiContestInProgressDoesNotExposeRealtimeRank() {
        when(contestService.getById(1L)).thenReturn(runningOiContest());

        var result = rankController.getRealtimeRankList(1L, 50);

        assertEquals(403, result.getCode());
    }

    @Test
    void oiContestInProgressDoesNotExposeMyOrFinalRank() {
        when(contestService.getById(1L)).thenReturn(runningOiContest());

        assertEquals(403, rankController.getMyRealtimeRank(1L).getCode());
        assertEquals(403, rankController.getFinalRankList(1L).getCode());
    }

    @Test
    void rankMutationEndpointsRequireAdmin() throws Exception {
        assertAdminOnly("finalizeContestRank");
        assertAdminOnly("rebuildRealtimeRank");
    }

    private void assertAdminOnly(String methodName) throws Exception {
        Method method = ContestRankController.class.getMethod(methodName, Long.class);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertTrue(annotation != null && annotation.value().contains("ADMIN"));
    }

    private Contest runningOiContest() {
        Contest contest = new Contest();
        contest.setId(1L);
        contest.setMode("OI");
        contest.setStartTime(LocalDateTime.now().minusMinutes(10));
        contest.setEndTime(LocalDateTime.now().plusMinutes(10));
        return contest;
    }
}
