package top.everblue.oj.api.contest;
/** 比赛域负责保密时间、题目可见性和班级参赛资格。 */
public interface ContestProblemAccessQuery {
    boolean canReadProblem(Long problemId, Long userId);
    /** 仅判定当前用户有资格访问的比赛，不能阻断其他班级的公开题练习。 */
    boolean hasRunningContest(Long problemId, Long userId);
}
