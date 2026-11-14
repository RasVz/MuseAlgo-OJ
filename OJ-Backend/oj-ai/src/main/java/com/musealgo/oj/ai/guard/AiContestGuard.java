package com.musealgo.oj.ai.guard;

import com.musealgo.oj.api.contest.ContestProblemAccessQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 比赛场景的 AI 封锁。
 * <p>请求显式带 contestId 时一律封锁；同时按 problemId 判定是否属于进行中的比赛，
 * 防止不传 contestId 从普通题目页绕过封锁。比赛结束后题目恢复正常，AI 随之恢复。</p>
 */
@Service
public class AiContestGuard {

    private ContestProblemAccessQuery contestProblems;

    @Autowired(required = false)
    public void setContestProblems(ContestProblemAccessQuery contestProblems) {
        this.contestProblems = contestProblems;
    }

    public boolean shouldBlock(Long contestId) {
        return contestId != null;
    }

    public boolean shouldBlock(Long contestId, Long problemId) {
        if (shouldBlock(contestId)) {
            return true;
        }
        return problemId != null && contestProblems != null
                && contestProblems.hasRunningFormalContestProblem(problemId);
    }
}
