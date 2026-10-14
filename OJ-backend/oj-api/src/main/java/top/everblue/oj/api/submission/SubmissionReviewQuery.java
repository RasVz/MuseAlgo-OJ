package top.everblue.oj.api.submission;

import java.util.List;
import java.util.Optional;

/**
 * AI 代码复盘（A2）读取提交的只读契约。
 * <p>
 * 只暴露「代码 + 评测特征」，测试点原文绝不进 prompt——
 * 用例明细仅以状态计数形式给出（防泄漏）。
 * </p>
 */
public interface SubmissionReviewQuery {

    /**
     * 按提交 ID 查询 AI 复盘数据（不存在返回空）。
     *
     * @param submissionId 提交 ID
     * @return 提交复盘数据（含代码与评测特征，不含测试点原文）
     */
    Optional<SubmissionReviewData> findById(Long submissionId);

    /** AI 复盘数据：代码 + 评测特征（含用例状态计数，不含用例原文）。 */
    record SubmissionReviewData(
            Long id,
            Long userId,
            Long problemId,
            Byte status,
            String language,
            String code,
            Integer score,
            Integer runTime,
            Integer runMemory,
            String errorMessage,
            Long contestId,
            List<CaseStatusCount> caseStats
    ) {
    }

    /** 某状态（status code）下的用例数量。 */
    record CaseStatusCount(int status, long count) {
    }
}
