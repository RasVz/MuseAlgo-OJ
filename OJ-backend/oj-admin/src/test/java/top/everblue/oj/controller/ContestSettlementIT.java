package top.everblue.oj.controller;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import top.everblue.oj.contest.entity.*;
import top.everblue.oj.contest.mapper.*;
import top.everblue.oj.contest.service.*;
import top.everblue.oj.judge.entity.Submission;
import top.everblue.oj.judge.mapper.SubmissionMapper;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"oj.messaging.rabbitmq-enabled=false","oj.contest.settlement-initial-delay-ms=86400000"})
class ContestSettlementIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ContestMapper contests;
    @Autowired SubmissionMapper submissions;
    @Autowired ContestRankService ranks;
    @Autowired VpAttemptMapper attempts;
    @Autowired VpAttemptService vp;
    @Autowired RankService globalRank;
    Contest contest;
    final List<Long> users=new ArrayList<>();
    final LocalDateTime start=LocalDateTime.of(2000,1,1,10,0);
    @BeforeEach void fixture() {
        for(int i=0;i<2;i++) {
            String name="settlement_"+UUID.randomUUID();
            jdbc.update("INSERT INTO user(username,password,nickname,rating) VALUES (?,'test-only',?,1200)",name,name);
            users.add(jdbc.queryForObject("SELECT id FROM user WHERE username=?",Long.class,name));
        }
        contest=new Contest();contest.setTitle("settlement-it");contest.setDuration(60);contest.setMode("ACM");
        contest.setStartTime(start);contest.setEndTime(start.plusHours(1));contest.setVisible(1);contest.setIsDeleted(0);contests.insert(contest);
    }
    Submission submit(long user,int status,int score,int minute,Long vpId) {
        var s=new Submission();s.setUserId(user);s.setProblemId(1L);s.setContestId(contest.getId());s.setVpAttemptId(vpId);
        s.setLanguage("cpp");s.setStatus((byte)status);s.setScore(score);s.setJudgePhase(status==0?"JUDGING":"FINISHED");s.setCreateTime(start.plusMinutes(minute));
        submissions.insert(s);return s;
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM submission WHERE contest_id=?",contest.getId());
        jdbc.update("DELETE FROM vp_attempt WHERE contest_id=?",contest.getId());
        jdbc.update("DELETE FROM user_rating WHERE contest_id=?",contest.getId());
        jdbc.update("DELETE FROM contest_rank WHERE contest_id=?",contest.getId());
        jdbc.update("DELETE FROM contest WHERE id=?",contest.getId());
        for(var id:users)jdbc.update("DELETE FROM user WHERE id=?",id);
        users.clear();
    }
    @Test void pendingThenConcurrentFinalizeAndReplayAreExactlyOnce() throws Exception {
        submit(users.get(0),2,0,2,null);var pending=submit(users.get(0),0,0,5,null);
        submit(users.get(1),1,100,10,null);submit(users.get(1),1,100,3,999L);
        assertThrows(IllegalStateException.class,()->ranks.finalizeContest(contest.getId()));
        assertFalse(ranks.hasFinalizedRank(contest.getId()));
        pending.setStatus((byte)1);pending.setScore(100);pending.setJudgePhase("FINISHED");submissions.updateById(pending);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var gate=new CountDownLatch(1);
            Callable<Void> call=()->{gate.await();ranks.finalizeContest(contest.getId());return null;};
            var a=pool.submit(call);var b=pool.submit(call);gate.countDown();a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        var result=ranks.getFinalRankList(contest.getId());
        assertEquals(2,result.size());assertEquals(users.get(1),result.get(0).getUserId());
        assertEquals(25,result.get(1).getPenaltyTime());
        var before=jdbc.queryForList("SELECT user_id,rating FROM user_rating WHERE contest_id=? ORDER BY user_id",contest.getId());
        for(int i=0;i<3;i++){ranks.rebuildRealtimeRankFromSubmission(contest.getId());ranks.finalizeContest(contest.getId());}
        assertEquals(before,jdbc.queryForList("SELECT user_id,rating FROM user_rating WHERE contest_id=? ORDER BY user_id",contest.getId()));
        assertEquals(2,before.size());assertNotNull(result.get(0).getRatingAfter());
        var total=globalRank.getTopRank(100).stream().filter(r->users.contains(r.getId())).toList();
        assertEquals(2,total.size());
        for(var row:total) assertEquals(jdbc.queryForObject("SELECT rating FROM user WHERE id=?",Integer.class,row.getId()),row.getRating());
    }
    @Test void emptyContestHasPersistentCompletionMarker() {
        ranks.finalizeContest(contest.getId());ranks.finalizeContest(contest.getId());
        assertTrue(ranks.hasFinalizedRank(contest.getId()));assertTrue(ranks.getFinalRankList(contest.getId()).isEmpty());
    }
    @Test void failedInfrastructureTaskDoesNotBlockSettlementOrCountAsWrong() {
        var failed=submit(users.get(0),0,0,1,null);failed.setJudgePhase("FAILED");submissions.updateById(failed);
        submit(users.get(0),1,100,10,null);ranks.finalizeContest(contest.getId());
        assertEquals(10,ranks.getFinalRankList(contest.getId()).get(0).getPenaltyTime());
    }
    @Test void oiVpWaitsForDelayedJudgeAndNeverChangesOfficialRankOrRating() {
        contest.setMode("OI");contests.updateById(contest);submit(users.get(0),2,40,5,null);ranks.finalizeContest(contest.getId());
        var official=ranks.getFinalRankList(contest.getId());assertEquals(40,official.get(0).getTotalScore());
        var attempt=new VpAttempt();attempt.setContestId(contest.getId());attempt.setUserId(users.get(1));
        attempt.setStatus(VpAttempt.STATUS_RUNNING);attempt.setStartTime(start);attempt.setEndTime(start.plusHours(1));attempts.insert(attempt);
        var pending=submit(users.get(1),0,0,20,attempt.getId());
        assertEquals("SETTLING",vp.finish(contest.getId(),users.get(1)).getStatus());
        pending.setStatus((byte)1);pending.setScore(100);pending.setJudgePhase("FINISHED");submissions.updateById(pending);
        var result=vp.getMy(contest.getId(),users.get(1));assertEquals("FINISHED",result.getStatus());assertEquals(100,result.getScore());
        assertEquals(1,result.getRankNum());assertEquals(official,ranks.getFinalRankList(contest.getId()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM user_rating WHERE contest_id=?",Integer.class,contest.getId()));
        assertEquals(result.getScore(),vp.finish(contest.getId(),users.get(1)).getScore());
    }
}
