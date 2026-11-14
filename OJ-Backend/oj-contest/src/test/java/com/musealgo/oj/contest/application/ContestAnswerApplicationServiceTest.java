package com.musealgo.oj.contest.application;

import com.musealgo.oj.api.contest.ContestProblemAccessQuery;
import com.musealgo.oj.api.problem.ContestProblemStatementQuery;
import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.common.exception.ForbiddenException;
import com.musealgo.oj.common.security.AuthenticatedUser;
import com.musealgo.oj.contest.entity.Contest;
import com.musealgo.oj.contest.entity.ContestUser;
import com.musealgo.oj.contest.entity.vo.ContestAnswerPaperVO;
import com.musealgo.oj.contest.entity.vo.ContestProblemVO;
import com.musealgo.oj.contest.mapper.ProblemContestMapper;
import com.musealgo.oj.contest.service.ContestService;
import com.musealgo.oj.contest.service.ContestUserService;
import com.musealgo.oj.contest.service.ProblemContestService;
import com.musealgo.oj.contest.service.VpAttemptService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContestAnswerApplicationServiceTest {

    private final ContestService contests = mock(ContestService.class);
    private final ContestUserService registrations = mock(ContestUserService.class);
    private final ProblemContestService problemContests = mock(ProblemContestService.class);
    private final ProblemContestMapper problemContestMapper = mock(ProblemContestMapper.class);
    private final VpAttemptService vpAttempts = mock(VpAttemptService.class);
    private final ContestProblemStatementQuery statements = mock(ContestProblemStatementQuery.class);
    private final ContestProblemAccessQuery contestAccess = mock(ContestProblemAccessQuery.class);
    private final ContestAnswerApplicationService service = new ContestAnswerApplicationService(
            contests, registrations, problemContests, problemContestMapper, vpAttempts, statements, contestAccess);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void login(long userId) {
        AuthenticatedUser actor = () -> userId;
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                actor, null, List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
        when(contestAccess.canAccessContest(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(userId)))
                .thenReturn(true);
    }

    private Contest contest(String mode, LocalDateTime start, LocalDateTime end, int review) {
        Contest contest = new Contest();
        contest.setId(1L);
        contest.setTitle("秋季赛");
        contest.setMode(mode);
        contest.setStartTime(start);
        contest.setEndTime(end);
        contest.setRegistrationReview(review);
        contest.setVisible(1);
        contest.setIsDeleted(0);
        when(contests.getById(1L)).thenReturn(contest);
        return contest;
    }

    private ContestUser registration(String status) {
        ContestUser user = new ContestUser();
        user.setContestId(1L);
        user.setUserId(10L);
        user.setStatus(status);
        when(registrations.findRegistration(1L, 10L)).thenReturn(user);
        return user;
    }

    private ContestProblemVO row(long problemId, String status) {
        ContestProblemVO row = new ContestProblemVO();
        row.setProblemId(problemId);
        row.setTitle("题目 " + problemId);
        row.setScore(100);
        row.setStatus(status);
        return row;
    }

    @Test
    void classContestWithoutMembershipIsRejected() {
        login(10L);
        contest("ACM", LocalDateTime.now().minusMinutes(10), LocalDateTime.now().plusHours(1), 0);
        registration("APPROVED");
        when(contestAccess.canAccessContest(1L, 10L)).thenReturn(false);

        assertThrows(ForbiddenException.class, () -> service.paper(1L));
    }

    @Test
    void runningContestWithApprovedRegistrationReturnsProblems() {
        login(10L);
        contest("ACM", LocalDateTime.now().minusMinutes(10), LocalDateTime.now().plusHours(1), 0);
        registration("APPROVED");
        when(problemContests.selectContestProblemList(1L, 1, 10L)).thenReturn(List.of(row(34L, "1")));

        ContestAnswerPaperVO paper = service.paper(1L);

        assertEquals("RUNNING", paper.getStatus());
        assertTrue(paper.getCanSubmit());
        assertEquals("VERDICT_TIME_MEMORY", paper.getResultVisibility());
        assertEquals(1, paper.getProblems().size());
        assertEquals("SOLVED", paper.getProblems().get(0).getMyStatus());
    }

    @Test
    void runningContestRequiresApprovedRegistrationWhenReviewEnabled() {
        login(10L);
        contest("ACM", LocalDateTime.now().minusMinutes(10), LocalDateTime.now().plusHours(1), 1);
        when(registrations.findRegistration(1L, 10L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.paper(1L));
    }

    @Test
    void pendingRegistrationCannotSeeProblems() {
        login(10L);
        contest("ACM", LocalDateTime.now().minusMinutes(10), LocalDateTime.now().plusHours(1), 1);
        registration("PENDING");

        assertThrows(BusinessException.class, () -> service.paper(1L));
    }

    @Test
    void notStartedContestOnlyReturnsCountdownWithoutProblems() {
        login(10L);
        contest("ACM", LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(3), 0);
        when(registrations.findRegistration(1L, 10L)).thenReturn(null);

        ContestAnswerPaperVO paper = service.paper(1L);

        assertEquals("NOT_STARTED", paper.getStatus());
        assertFalse(paper.getCanSubmit());
        assertTrue(paper.getProblems().isEmpty());
        assertTrue(paper.getRemainingSeconds() > 0);
    }

    @Test
    void endedContestIsReadOnlyForParticipantsAndClosedForOthers() {
        login(10L);
        contest("ACM", LocalDateTime.now().minusHours(3), LocalDateTime.now().minusHours(1), 0);
        when(registrations.findRegistration(1L, 10L)).thenReturn(null);
        assertThrows(ForbiddenException.class, () -> service.paper(1L));

        registration("APPROVED");
        when(problemContests.selectContestProblemList(1L, 1, 10L)).thenReturn(List.of(row(34L, "2")));
        ContestAnswerPaperVO paper = service.paper(1L);
        assertEquals("ENDED", paper.getStatus());
        assertFalse(paper.getCanSubmit());
        assertEquals("FULL", paper.getResultVisibility());
        assertEquals("ATTEMPTED", paper.getProblems().get(0).getMyStatus());
    }

    @Test
    void oiContestDuringRunningOnlyRevealsSubmitted() {
        login(10L);
        contest("OI", LocalDateTime.now().minusMinutes(10), LocalDateTime.now().plusHours(1), 0);
        registration("AUTO_APPROVED");
        when(problemContests.selectContestProblemList(1L, 1, 10L)).thenReturn(List.of(row(34L, "2")));

        ContestAnswerPaperVO paper = service.paper(1L);

        assertEquals("SUBMITTED_ONLY", paper.getResultVisibility());
        assertEquals("SUBMITTED", paper.getProblems().get(0).getMyStatus());
    }
}
