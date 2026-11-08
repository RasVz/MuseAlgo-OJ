package com.musealgo.oj.common.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.musealgo.oj.contest.entity.*;
import com.musealgo.oj.contest.mapper.VpAttemptMapper;
import com.musealgo.oj.contest.service.*;
import java.time.LocalDateTime;

/** 有/无MQ均轮询兜底；按结束时间顺序结算，并回收超时 VP 会话。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContestRankFinalizeTask {
    private final ContestService contests;
    private final ContestRankService ranks;
    private final VpAttemptMapper attempts;
    private final VpAttemptService vp;

    @Scheduled(fixedDelayString="${oj.contest.settlement-interval-ms:5000}",initialDelayString="${oj.contest.settlement-initial-delay-ms:5000}")
    public void finalizeEndedContests() {
        var now=LocalDateTime.now();
        var ended=contests.list(new LambdaQueryWrapper<Contest>().isNull(Contest::getRankFinalizedAt)
                .isNotNull(Contest::getEndTime).lt(Contest::getEndTime,now)
                .orderByAsc(Contest::getEndTime).orderByAsc(Contest::getId));
        for(var contest:ended) {
            try{ranks.finalizeContest(contest.getId());}
            catch(IllegalStateException e){log.debug("比赛等待结算 contestId={}: {}",contest.getId(),e.getMessage());}
            catch(Exception e){log.error("比赛结算失败 contestId={}",contest.getId(),e);}
        }
        var pending=attempts.selectList(new LambdaQueryWrapper<VpAttempt>().and(q->q.eq(VpAttempt::getStatus,VpAttempt.STATUS_SETTLING)
                .or(w->w.eq(VpAttempt::getStatus,VpAttempt.STATUS_RUNNING).le(VpAttempt::getEndTime,now))));
        for(var attempt:pending) {
            try{vp.finish(attempt.getContestId(),attempt.getUserId());}
            catch(Exception e){log.error("VP结算失败 attemptId={}",attempt.getId(),e);}
        }
    }
}
