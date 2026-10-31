package com.musealgo.oj.api.submission;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 提交上下文只读契约（oj-judge 实现，oj-game/oj-analytics 等模块消费）。
 */
public interface SubmissionQuery {

    /**
     * 按提交 ID 查询提交上下文（不存在返回空）。
     *
     * @param submissionId 提交 ID
     * @return 提交上下文（id/userId/problemId/contestId/status/score 的只读投射）
     */
    Optional<SubmissionContext> findById(Long submissionId);

    /**
     * 查询某用户全部 AC 提交，按 (create_time, id) 升序。
     * <p>
     * 用于 T8 重判后的标签 ELO 重建：ELO 是按 AC 事件顺序累积的派生值，
     * 必须按历史顺序重放提交才能得到与旧链路一致的结果。
     * </p>
     */
    List<AcceptedSubmission> findAcceptedChronological(Long userId);

    /** 提交上下文只读投射：id/userId/problemId/contestId/status/score。 */
    record SubmissionContext(Long id, Long userId, Long problemId, Long contestId,
                             Byte status, Integer score) {
    }

    /** AC 提交的只读投射（ELO 重建用）。 */
    record AcceptedSubmission(Long submissionId, Long problemId, LocalDateTime createTime) {
    }
}
