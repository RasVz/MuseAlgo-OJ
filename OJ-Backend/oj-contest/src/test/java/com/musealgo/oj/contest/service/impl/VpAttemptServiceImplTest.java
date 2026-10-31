package com.musealgo.oj.contest.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import com.musealgo.oj.api.problem.ProblemQuery;
import com.musealgo.oj.api.submission.ContestVpQuery;
import com.musealgo.oj.contest.entity.Contest;
import com.musealgo.oj.contest.entity.ContestRank;
import com.musealgo.oj.contest.entity.VpAttempt;
import com.musealgo.oj.contest.entity.vo.VpAttemptVO;
import com.musealgo.oj.contest.mapper.ContestRankMapper;
import com.musealgo.oj.contest.mapper.ProblemContestMapper;
import com.musealgo.oj.contest.mapper.VpAttemptMapper;
import com.musealgo.oj.contest.service.ContestService;
import com.musealgo.oj.contest.sse.ContestRankSsePublisher;

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
                mock(ContestRankMapper.class), mock(ContestVpQuery.class), mock(ContestRankSsePublisher.class));
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
        assertEquals(VpAttemptVO.RANK_BASIS_OFFICIAL_REFERENCE, result.getRankBasis());
        verify(contestService).getByIdForUpdate(1L);
        verify(attemptMapper, never()).insert(any(VpAttempt.class));
    }

    @Test
    void acmFinishUsesAcceptedProblemsAndWrongAttemptPenalty() {
        VpAttemptMapper attemptMapper = mock(VpAttemptMapper.class);
        ContestService contestService = mock(ContestService.class);
        ContestRankMapper rankMapper = mock(ContestRankMapper.class);
        ContestVpQuery vpQuery = mock(ContestVpQuery.class);
        ContestRankSsePublisher rankSsePublisher = mock(ContestRankSsePublisher.class);
        VpAttemptServiceImpl service = service(attemptMapper, contestService, rankMapper, vpQuery, rankSsePublisher);
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
        assertEquals(VpAttemptVO.RANK_BASIS_OFFICIAL_REFERENCE, result.getRankBasis());
        verify(attemptMapper,org.mockito.Mockito.times(2)).updateById(attempt);
        verify(rankSsePublisher).globalChanged(1L);
    }

    @Test
    void finishedVpRankComparesAgainstOfficialRowsOnly() {
        VpAttemptMapper attemptMapper = mock(VpAttemptMapper.class);
        ContestService contestService = mock(ContestService.class);
        ContestRankMapper rankMapper = mock(ContestRankMapper.class);
        ContestVpQuery vpQuery = mock(ContestVpQuery.class);
        ContestRankSsePublisher rankSsePublisher = mock(ContestRankSsePublisher.class);
        VpAttemptServiceImpl service = service(attemptMapper, contestService, rankMapper, vpQuery, rankSsePublisher);
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
        contest.setId(1L);
        contest.setMode("ACM");
        contest.setRankFinalizedAt(LocalDateTime.now().minusDays(1));
        when(contestService.getById(1L)).thenReturn(contest);
        when(vpQuery.findSubmissions(9L)).thenReturn(List.of(
                new ContestVpQuery.VpSubmission(100L, (byte) 1, start.plusMinutes(10))));
        when(rankMapper.selectList(any())).thenReturn(List.of(
                official(21L, 2, 10),
                official(22L, 1, 5),
                official(23L, 1, 50)));

        VpAttemptVO result = service.finish(1L, 10L);

        // VP: 1 题、罚时 10 分钟。21 过题更多、22 同题数但罚时更少，均更强；23 更弱。
        assertEquals(3, result.getRankNum());
        assertEquals(4, result.getTotalParticipants());
        assertEquals(VpAttemptVO.RANK_BASIS_OFFICIAL_REFERENCE, result.getRankBasis());
        assertEquals(VpAttempt.STATUS_FINISHED, result.getStatus());
        verify(rankMapper, never()).insert(any(ContestRank.class));
    }

    @Test
    void oiVpRankUsesStrictlyHigherOfficialTotalScore() {
        VpAttemptMapper attemptMapper = mock(VpAttemptMapper.class);
        ContestService contestService = mock(ContestService.class);
        ContestRankMapper rankMapper = mock(ContestRankMapper.class);
        ContestVpQuery vpQuery = mock(ContestVpQuery.class);
        VpAttemptServiceImpl service = service(attemptMapper, contestService, rankMapper, vpQuery,
                mock(ContestRankSsePublisher.class));
        VpAttempt attempt = new VpAttempt();
        attempt.setId(9L);
        attempt.setContestId(1L);
        attempt.setUserId(10L);
        attempt.setStatus(VpAttempt.STATUS_RUNNING);
        attempt.setStartTime(LocalDateTime.now().minusHours(2));
        attempt.setEndTime(LocalDateTime.now().minusHours(1));
        when(attemptMapper.selectOne(any())).thenReturn(attempt);
        Contest contest = new Contest();
        contest.setId(1L);
        contest.setMode("OI");
        contest.setRankFinalizedAt(LocalDateTime.now().minusDays(1));
        when(contestService.getById(1L)).thenReturn(contest);
        when(vpQuery.findBestScores(9L)).thenReturn(List.of(
                new ContestVpQuery.VpProblemScore(100L, 100),
                new ContestVpQuery.VpProblemScore(200L, 50)));
        when(rankMapper.selectList(any())).thenReturn(List.of(
                officialScore(21L, 200),
                officialScore(22L, 150),
                officialScore(23L, 100)));

        VpAttemptVO result = service.finish(1L, 10L);

        // 与正式选手同分不排在后面：只有严格更高总分才算更强，故参考名次为 2。
        assertEquals(150, result.getScore());
        assertEquals(2, result.getRankNum());
        assertEquals(4, result.getTotalParticipants());
        assertEquals(VpAttemptVO.RANK_BASIS_OFFICIAL_REFERENCE, result.getRankBasis());
    }

    @Test
    void historicalFinishedAttemptGetsRankBasisWithoutBeingRewritten() {
        VpAttemptMapper attemptMapper = mock(VpAttemptMapper.class);
        ContestService contestService = mock(ContestService.class);
        VpAttemptServiceImpl service = service(attemptMapper, contestService,
                mock(ContestRankMapper.class), mock(ContestVpQuery.class), mock(ContestRankSsePublisher.class));
        Contest contest = new Contest();
        contest.setId(1L);
        contest.setEndTime(LocalDateTime.now().minusDays(1));
        when(contestService.getByIdForUpdate(1L)).thenReturn(contest);
        VpAttempt historical = new VpAttempt();
        historical.setId(8L);
        historical.setContestId(1L);
        historical.setUserId(10L);
        historical.setStatus(VpAttempt.STATUS_FINISHED);
        historical.setRankNum(7);
        historical.setTotalParticipants(20);
        when(attemptMapper.selectOne(any())).thenReturn(null, historical);

        VpAttemptVO result = service.getMy(1L, 10L);

        assertEquals(7, result.getRankNum());
        assertEquals(20, result.getTotalParticipants());
        assertEquals(VpAttemptVO.RANK_BASIS_OFFICIAL_REFERENCE, result.getRankBasis());
        verify(attemptMapper, never()).updateById(any(VpAttempt.class));
    }

    private static ContestRank official(Long userId, int solved, int penalty) {
        ContestRank row = new ContestRank();
        row.setUserId(userId);
        row.setSolvedCount(solved);
        row.setPenaltyTime(penalty);
        row.setTotalScore(0);
        return row;
    }

    private static ContestRank officialScore(Long userId, int score) {
        ContestRank row = new ContestRank();
        row.setUserId(userId);
        row.setSolvedCount(0);
        row.setPenaltyTime(0);
        row.setTotalScore(score);
        return row;
    }

    @Test
    void vpRejudgeInvalidatesOnlyTheGlobalBoard() {
        VpAttemptMapper attemptMapper = mock(VpAttemptMapper.class);
        ContestService contestService = mock(ContestService.class);
        ContestRankMapper rankMapper = mock(ContestRankMapper.class);
        ContestVpQuery vpQuery = mock(ContestVpQuery.class);
        ContestRankSsePublisher rankSsePublisher = mock(ContestRankSsePublisher.class);
        VpAttemptServiceImpl service = service(attemptMapper, contestService, rankMapper, vpQuery, rankSsePublisher);
        VpAttempt attempt = new VpAttempt();
        attempt.setId(9L);
        attempt.setContestId(1L);
        attempt.setUserId(10L);
        attempt.setStatus(VpAttempt.STATUS_FINISHED);
        attempt.setStartTime(LocalDateTime.now().minusHours(1));
        attempt.setEndTime(LocalDateTime.now().minusMinutes(30));
        when(attemptMapper.selectById(9L)).thenReturn(attempt);
        Contest contest = new Contest();
        contest.setId(1L);
        contest.setMode("ACM");
        contest.setRankFinalizedAt(LocalDateTime.now().minusDays(1));
        when(contestService.getById(1L)).thenReturn(contest);
        when(vpQuery.hasPending(9L)).thenReturn(false);
        when(vpQuery.findSubmissions(9L)).thenReturn(List.of());
        when(rankMapper.selectList(any())).thenReturn(List.of());

        service.recalculateForRejudge(9L);

        verify(rankSsePublisher).globalChanged(1L);
        verify(rankSsePublisher, never()).officialAndGlobalChanged(1L);
    }

    private static VpAttemptServiceImpl service(VpAttemptMapper attemptMapper,
                                                ContestService contestService,
                                                ContestRankMapper rankMapper,
                                                ContestVpQuery vpQuery,
                                                ContestRankSsePublisher rankSsePublisher) {
        return new VpAttemptServiceImpl(
                attemptMapper,
                contestService,
                rankMapper,
                mock(ProblemContestMapper.class),
                mock(ProblemQuery.class),
                vpQuery,
                rankSsePublisher);
    }
}
