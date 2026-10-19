package top.everblue.oj.contest.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import top.everblue.oj.api.problem.ProblemQuery;
import top.everblue.oj.api.submission.ContestVpQuery;
import top.everblue.oj.contest.entity.Contest;
import top.everblue.oj.contest.entity.VpAttempt;
import top.everblue.oj.contest.entity.vo.VpAttemptVO;
import top.everblue.oj.contest.mapper.ContestRankMapper;
import top.everblue.oj.contest.mapper.ProblemContestMapper;
import top.everblue.oj.contest.mapper.VpAttemptMapper;
import top.everblue.oj.contest.service.ContestService;

import java.time.LocalDateTime;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VpAttemptServiceImplTest {
    @org.junit.jupiter.api.BeforeAll
    static void mybatisMetadata() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "test"), VpAttempt.class);
    }


    @Test
    void getMyFinalizesExpiredAttemptInsideTransaction() throws Exception {
        Method method = VpAttemptServiceImpl.class.getMethod("getMy", Long.class, Long.class);

        assertNotNull(method.getAnnotation(Transactional.class));
    }

    @Test
    void startReusesRunningAttemptInsideLockedContest() {
        VpAttemptMapper attemptMapper = mock(VpAttemptMapper.class);
        ContestService contestService = mock(ContestService.class);
        VpAttemptServiceImpl service = service(attemptMapper, contestService,
                mock(ContestRankMapper.class), mock(ContestVpQuery.class));
        Contest contest = new Contest();
        contest.setId(1L);
        contest.setEndTime(LocalDateTime.now().minusHours(1));
        when(contestService.getByIdForUpdate(1L)).thenReturn(contest);
        VpAttempt running = new VpAttempt();
        running.setId(9L);
        running.setContestId(1L);
        running.setUserId(10L);
        running.setStatus(VpAttempt.STATUS_RUNNING);
        running.setStartTime(LocalDateTime.now().minusMinutes(5));
        running.setEndTime(LocalDateTime.now().plusMinutes(30));
        when(attemptMapper.selectOne(any())).thenReturn(running);

        VpAttemptVO result = service.start(1L, 10L);

        assertEquals(9L, result.getId());
        verify(contestService).getByIdForUpdate(1L);
        verify(attemptMapper, never()).insert(any(VpAttempt.class));
    }

    @Test
    void acmFinishUsesAcceptedProblemsAndWrongAttemptPenalty() {
        VpAttemptMapper attemptMapper = mock(VpAttemptMapper.class);
        ContestService contestService = mock(ContestService.class);
        ContestRankMapper rankMapper = mock(ContestRankMapper.class);
        ContestVpQuery vpQuery = mock(ContestVpQuery.class);
        VpAttemptServiceImpl service = service(attemptMapper, contestService, rankMapper, vpQuery);
        LocalDateTime start = LocalDateTime.of(2026, 8, 2, 10, 0);
        VpAttempt attempt = new VpAttempt();
        attempt.setId(9L);
        attempt.setContestId(1L);
        attempt.setUserId(10L);
        attempt.setStatus(VpAttempt.STATUS_RUNNING);
        attempt.setStartTime(start);
        attempt.setEndTime(start.plusHours(2));
        when(attemptMapper.selectOne(any())).thenReturn(attempt);
        Contest contest = new Contest();
        contest.setMode("ACM");
        contest.setRankFinalizedAt(LocalDateTime.now().minusDays(1));
        when(contestService.getById(1L)).thenReturn(contest);
        when(vpQuery.findSubmissions(9L)).thenReturn(List.of(
                new ContestVpQuery.VpSubmission(100L, (byte) 2, start.plusMinutes(5)),
                new ContestVpQuery.VpSubmission(100L, (byte) 1, start.plusMinutes(10)),
                new ContestVpQuery.VpSubmission(200L, (byte) 1, start.plusMinutes(30))));
        when(rankMapper.selectList(any())).thenReturn(List.of());

        VpAttemptVO result = service.finish(1L, 10L);

        assertEquals(2, result.getSolvedCount());
        assertEquals(60, result.getPenaltyTime());
        assertEquals(1, result.getTotalParticipants());
        assertEquals(VpAttempt.STATUS_FINISHED, result.getStatus());
        verify(attemptMapper,org.mockito.Mockito.times(2)).updateById(attempt);
    }

    private static VpAttemptServiceImpl service(VpAttemptMapper attemptMapper,
                                                ContestService contestService,
                                                ContestRankMapper rankMapper,
                                                ContestVpQuery vpQuery) {
        return new VpAttemptServiceImpl(
                attemptMapper,
                contestService,
                rankMapper,
                mock(ProblemContestMapper.class),
                mock(ProblemQuery.class),
                vpQuery);
    }
}
