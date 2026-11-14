package com.musealgo.oj.api.contest;

import java.util.Set;

/** 比赛域负责保密时间、题目可见性和班级参赛资格。 */
public interface ContestProblemAccessQuery {
    boolean canReadProblem(Long problemId, Long userId);
    /** 仅判定当前用户有资格访问的比赛，不能阻断其他班级的公开题练习。 */
    boolean hasRunningContest(Long problemId, Long userId);

    /**
     * 该题是否属于一场正在进行中的公开比赛。
     * <p>用于比赛进行期封锁通用题目详情、题解、讨论与 AI 等旁路；与当前用户是否报名无关，
     * 已参赛用户也必须从比赛答题页进入。比赛结束后返回 {@code false}，按题目公开状态恢复。</p>
     */
    default boolean hasRunningFormalContestProblem(Long problemId) {
        return false;
    }

    /** 当前进行中公开比赛包含的全部题目 ID，用于题库列表/搜索/推荐过滤。 */
    default Set<Long> findRunningFormalContestProblemIds() {
        return Set.of();
    }
}
