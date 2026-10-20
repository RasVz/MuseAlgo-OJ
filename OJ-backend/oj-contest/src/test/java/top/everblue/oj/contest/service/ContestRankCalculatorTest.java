package top.everblue.oj.contest.service;

import org.junit.jupiter.api.Test;
import top.everblue.oj.api.submission.ContestSubmission;
import top.everblue.oj.contest.entity.Contest;
import top.everblue.oj.contest.service.impl.EloRatingCalculator;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContestRankCalculatorTest {
    final LocalDateTime start=LocalDateTime.of(2026,1,1,10,0);
    Contest contest(String mode){var c=new Contest();c.setId(1L);c.setMode(mode);c.setStartTime(start);c.setEndTime(start.plusHours(1));return c;}
    ContestSubmission submission(long id,long user,int status,int score,int minute,Long vp){
        return new ContestSubmission(id,1L,vp,user,10L,(byte)status,"cpp",start.plusMinutes(minute),score);
    }
    @Test void replayOrderDuplicatesPendingAndVpDoNotAffectAcm() {
        var rows=List.of(submission(1,2,2,0,2,null),submission(2,2,1,100,5,null),
                submission(3,2,1,100,6,null),submission(4,3,0,0,1,null),submission(5,3,1,100,3,9L),
                submission(6,4,1,100,61,null),submission(7,5,1,100,-1,null));
        var input=new ArrayList<>(rows);input.add(rows.get(0));Collections.reverse(input);
        var calc=new ContestRankCalculator();var actual=calc.calculate(contest("ACM"),input);
        assertEquals(calc.calculate(contest("ACM"),rows),actual);
        assertEquals(1,actual.size());assertEquals(1,actual.get(0).getSolvedCount());assertEquals(25,actual.get(0).getPenaltyTime());
    }
    @Test void oiUsesPerProblemBestAndIncludesZeroScoreParticipants() {
        var rows=new ContestRankCalculator().calculate(contest("OI"),List.of(
                submission(1,2,2,30,1,null),submission(2,2,2,70,2,null),submission(3,2,2,40,3,null),
                submission(4,3,2,0,1,null),submission(5,4,2,70,4,null)));
        assertEquals(List.of(70,70,0),rows.stream().map(r->r.getTotalScore()).toList());
        assertEquals(List.of(1,1,3),rows.stream().map(r->r.getRankNum()).toList());
    }
    @Test void equalAcmResultsHaveSameRankAndRatingWithNoUserIdBias() {
        var rows=new ContestRankCalculator().calculate(contest("ACM"),List.of(
                submission(1,5,1,100,5,null),submission(2,2,1,100,5,null)));
        assertEquals(List.of(1,1),rows.stream().map(r->r.getRankNum()).toList());
        var ratings=new EloRatingCalculator().calculate(1L,List.of(2L,5L),List.of(1200,1200),List.of(1,1));
        assertEquals(ratings.get(0).getRating(),ratings.get(1).getRating());
        assertEquals(1,ratings.get(1).getRank());
    }
}
