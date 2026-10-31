package com.musealgo.oj.contest.service.impl;

import org.junit.jupiter.api.Test;
import com.musealgo.oj.api.submission.*;
import com.musealgo.oj.api.user.*;
import com.musealgo.oj.contest.entity.Contest;
import com.musealgo.oj.contest.entity.ContestSettlement;
import com.musealgo.oj.contest.mapper.*;
import com.musealgo.oj.contest.service.*;
import java.time.LocalDateTime;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ContestRankServiceImplTest {
    @Test void pendingSubmissionMustNotBecomeWrongAttempt() {
        var query=mock(ContestSubmissionQuery.class);var contests=mock(ContestMapper.class);
        var c=new Contest();c.setId(1L);c.setMode("ACM");c.setStartTime(LocalDateTime.now().minusHours(1));c.setEndTime(LocalDateTime.now().plusHours(1));
        when(contests.selectById(1L)).thenReturn(c);
        when(query.findByContestId(1L)).thenReturn(List.of(new ContestSubmission(9L,1L,null,2L,3L,(byte)0,"cpp",LocalDateTime.now(),0)));
        var service=new ContestRankServiceImpl(mock(ContestRankMapper.class),contests,query,new ContestRankCalculator(),
                mock(RatingCalculator.class),mock(UserRatingService.class),mock(UserQuery.class),mock(UserRatingCommand.class),
                mock(ContestUserMapper.class),mock(VpAttemptMapper.class),mock(ContestSettlementMapper.class),
                mock(com.musealgo.oj.contest.sse.ContestRankSsePublisher.class));
        assertTrue(service.getRealtimeTopN(1L,10).isEmpty());
    }

    @Test void finalizationPublishesBothBoardsAfterPersistingTheSnapshot() {
        var query=mock(ContestSubmissionQuery.class);var contests=mock(ContestMapper.class);
        var ranks=mock(ContestRankMapper.class);var settlements=mock(ContestSettlementMapper.class);
        var rankSsePublisher=mock(com.musealgo.oj.contest.sse.ContestRankSsePublisher.class);
        var end=LocalDateTime.now().minusMinutes(1);
        var contest=new Contest();contest.setId(2L);contest.setMode("ACM");
        contest.setStartTime(end.minusHours(1));contest.setEndTime(end);
        when(contests.lockSettlement()).thenReturn(1);
        when(contests.selectByIdForUpdate(2L)).thenReturn(contest);
        when(contests.countEarlierUnsettled(eq(2L),any())).thenReturn(0L);
        when(query.hasPending(2L)).thenReturn(false);
        when(query.findByContestId(2L)).thenReturn(List.of());
        when(contests.markRankFinalized(2L)).thenReturn(1);
        var service=new ContestRankServiceImpl(ranks,contests,query,new ContestRankCalculator(),
                mock(RatingCalculator.class),mock(UserRatingService.class),mock(UserQuery.class),mock(UserRatingCommand.class),
                mock(ContestUserMapper.class),mock(VpAttemptMapper.class),settlements,rankSsePublisher);

        service.finalizeContest(2L);

        var inOrder=inOrder(ranks,contests,settlements,rankSsePublisher);
        inOrder.verify(ranks).deleteByContestId(2L);
        inOrder.verify(contests).markRankFinalized(2L);
        inOrder.verify(settlements).insert(any(ContestSettlement.class));
        inOrder.verify(rankSsePublisher).officialAndGlobalChanged(2L);
    }
}
