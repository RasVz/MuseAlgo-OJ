package top.everblue.oj.api.submission;

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
     * @return 每题最好成绩列表（未提交的题目不出现在结果中）
     */
    List<SubmissionAttemptScore> findBestScores(
            Long userId, Collection<Long> problemIds,
            LocalDateTime from, LocalDateTime to);

    /** 某题的本次最好成绩。 */
    record SubmissionAttemptScore(Long problemId, int bestScore) {
    }
}
