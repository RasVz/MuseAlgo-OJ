package top.everblue.oj.api.submission;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 提交代码只读契约（oj-judge 实现，oj-analytics 查重引擎消费）。
 * <p>
 * 仅暴露查重所需的最小字段（id/userId/problemId/code/status/createTime），
 * 不暴露判题明细、错误信息等其他 submission 字段。
 * </p>
 */
public interface SubmissionCodeQuery {

    /**
     * 按班级 + 题目取提交代码（经 class_student 归属过滤，含班级全部成员的提交）。
     *
     * @param classId   班级 ID
     * @param problemId 题目 ID
     * @param since     起始时间（含），可为 null 表示不限
     * @return 按提交时间升序的代码投射
     */
    List<CodePairSource> findByClassAndProblem(Long classId, Long problemId, LocalDateTime since);

    /**
     * 按提交 ID 批量取代码（查重报告展开对比用）。
     *
     * @param submissionIds 提交 ID 集合
     * @return 命中提交的代码投射（不存在的不返回）
     */
    List<CodePairSource> findBySubmissionIds(Collection<Long> submissionIds);

    /**
     * 按比赛 + 题目取提交代码（比赛维度跨班比对，查重 M2）。
     * <p>
     * 含该场比赛的全部有效提交（含 VP 会话提交），按提交时间升序。
     * </p>
     *
     * @param contestId 比赛 ID
     * @param problemId 题目 ID
     * @param since     起始时间（含），可为 null 表示不限
     * @return 按提交时间升序的代码投射
     */
    List<CodePairSource> findByContestAndProblem(Long contestId, Long problemId, LocalDateTime since);

    /**
     * 比赛涉及的全部题目 ID（赛后自动查重枚举题目用）。
     *
     * @param contestId 比赛 ID
     * @return 去重后的题目 ID 列表（无提交返回空列表）
     */
    List<Long> findContestProblemIds(Long contestId);

    /** 提交代码只读投射：仅暴露查重需要的字段。 */
    record CodePairSource(Long submissionId, Long userId, Long problemId, String code,
                          String status, LocalDateTime createTime) {
    }
}
