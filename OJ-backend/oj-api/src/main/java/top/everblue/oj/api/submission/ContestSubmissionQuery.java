package top.everblue.oj.api.submission;

import java.util.List;
import java.util.Optional;

/** 比赛提交只读查询契约。消费者不得直接访问 submission mapper。 */
public interface ContestSubmissionQuery {

    /** 正式比赛时间窗内是否仍有未终结判题（FAILED 为终态，VP 不计）。 */
    boolean hasPending(Long contestId);

    /**
     * 查询某场比赛的正式提交流水（可能包含待判，调用方按计分规则过滤）。
     *
     * @param contestId 比赛 ID
     * @return 提交列表（按提交时间升序）
     */
    List<ContestSubmission> findByContestId(Long contestId);

    /**
     * 分页查询比赛提交历史。
     *
     * @param request 分页与过滤条件（比赛 ID/题目/语言/状态/仅本人等）
     * @return 分页结果
     */
    PageResult<ContestSubmission> findPage(ContestSubmissionPageRequest request);

    /**
     * 按 ID 查询单条提交记录（只读视图）。
     * 消费者（如 ContestRankConsumer）用此方法替代直接注入 SubmissionMapper。
     */
    Optional<ContestSubmission> findById(Long submissionId);
}
