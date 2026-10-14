package top.everblue.oj.api.contest;

/**
 * 比赛查重状态写契约（oj-contest 实现，oj-analytics 赛后自动查重调度器消费）。
 * <p>
 * 跨模块写操作统一走契约：调度器不直接注入 ContestMapper。
 * </p>
 */
public interface ContestPlagiarismCommand {

    /**
     * 标记比赛「赛后查重已执行」。
     *
     * @param contestId 比赛 ID
     */
    void markContestChecked(Long contestId);
}
