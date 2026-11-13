package com.musealgo.oj.api.problem;

import java.time.LocalDateTime;

/**
 * 成套试卷作答会话的提交/读取权限契约。
 *
 * <p>提交模块只认「这次提交属于哪次作答会话」，不直接读试卷表；
 * 由 oj-problem 实现并统一校验会话归属、状态、时间窗和题目归属。</p>
 */
public interface ExamAttemptAccessQuery {

    /**
     * 校验当前用户可以在该会话中提交该题，并返回会话信息。
     *
     * @throws com.musealgo.oj.common.exception.BusinessException 会话不存在、不属于本人、
     *         已交卷、已超时或题目不属于试卷时抛出
     */
    AttemptAccess requireActiveAttempt(Long attemptId, Long problemId, Long userId);

    /** 会话最小访问信息。 */
    record AttemptAccess(
            Long attemptId,
            Long paperId,
            Long userId,
            LocalDateTime startTime,
            LocalDateTime endTime) {
    }
}
