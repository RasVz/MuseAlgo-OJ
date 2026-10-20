package top.everblue.oj.contest.service.impl;

import org.junit.jupiter.api.Test;
import top.everblue.oj.api.submission.*;
import top.everblue.oj.api.user.*;
import top.everblue.oj.contest.entity.Contest;
import top.everblue.oj.contest.mapper.*;
import top.everblue.oj.contest.service.*;
import java.time.LocalDateTime;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ContestRankServiceImplTest {
    @Test void pendingSubmissionMustNotBecomeWrongAttempt() {
        var query=mock(ContestSubmissionQuery.class);var contests=mock(ContestMapper.class);
        var c=new Contest();c.setId(1L);c.setMode("ACM");c.setStartTime(LocalDateTime.now().minusHours(1));c.setEndTime(LocalDateTime.now().plusHours(1));
        when(contests.selectById(1L)).thenReturn(c);
        when(query.findByContestId(1L)).thenReturn(List.of(new ContestSubmission(9L,1L,null,2L,3L,(byte)0,"cpp",LocalDateTime.now(),0)));
        var service=new ContestRankServiceImpl(mock(ContestRankMapper.class),contests,query,new ContestRankCalculator(),
                mock(RatingCalculator.class),mock(UserRatingService.class),mock(UserQuery.class),mock(UserRatingCommand.class));
        assertTrue(service.getRealtimeTopN(1L,10).isEmpty());
    }
}
