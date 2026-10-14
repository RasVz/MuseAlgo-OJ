package top.everblue.oj.api.submission;

import java.util.Optional;

/**
 * 提交上下文只读契约（oj-judge 实现，oj-game 等模块消费）。
 */
public interface SubmissionQuery {

    /**
     * 按提交 ID 查询提交上下文（不存在返回空）。
     *
     * @param submissionId 提交 ID
     * @return 提交上下文（id/userId/problemId/contestId/status/score 的只读投射）
     */
    Optional<SubmissionContext> findById(Long submissionId);

    /** 提交上下文只读投射：id/userId/problemId/contestId/status/score。 */
    record SubmissionContext(Long id, Long userId, Long problemId, Long contestId,
                             Byte status, Integer score) {
    }
}
