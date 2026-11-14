package com.musealgo.oj.api.submission;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 训练试卷会话编程题得分契约：查某用户在某时间窗内、指定题目集合的每题最好成绩。
 */
public interface SubmissionAttemptQuery {

    /**
     * 查询用户在某时间窗内、指定题目集合的每题最好成绩。
     *
     * @param userId      用户 ID
     * @param problemIds  题目 ID 集合
     * @param from        时间窗起点（含）
     * @param to          时间窗终点（含）
     * @return 每题最好成绩列表（未提交的题目不出现在结果中），只包含普通练习提交
     */
    List<SubmissionAttemptScore> findBestScores(
            Long userId, Collection<Long> problemIds,
            LocalDateTime from, LocalDateTime to);

    /** 尚在判题中的普通练习提交数；交卷前等待这些提交进入终态。 */
    long countPending(
            Long userId, Collection<Long> problemIds,
            LocalDateTime from, LocalDateTime to);

    /** 按作答会话绑定查询每题最好成绩，不依赖时间窗。 */
    List<SubmissionAttemptScore> findBestScoresByAttempt(
            Long attemptId, Collection<Long> problemIds);

    /** 按作答会话绑定查询仍在判题的提交数。 */
    long countPendingByAttempt(Long attemptId, Collection<Long> problemIds);

    /** 按作答会话绑定查询每题提交摘要，用于整卷页恢复状态。 */
    List<SubmissionAttemptSummary> summarizeByAttempt(
            Long attemptId, Collection<Long> problemIds);

    /** 某题的本次最好成绩。 */
    record SubmissionAttemptScore(Long problemId, int bestScore) {
    }

    /** 某题在本次会话中的提交摘要。 */
    record SubmissionAttemptSummary(
            Long problemId, int bestScore, int attemptCount, int pendingCount) {
    }
}
