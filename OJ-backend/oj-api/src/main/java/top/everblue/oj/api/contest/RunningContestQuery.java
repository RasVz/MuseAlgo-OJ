package top.everblue.oj.api.contest;

import java.time.LocalDateTime;

/**
 * 进行中比赛的只读查询契约。
 * <p>
 * 典型消费者：oj-ai 的比赛防护栏——题目处于进行中比赛时，
 * 禁止 AI 对该题输出解题帮助，防止实时作弊。
 * </p>
 */
public interface RunningContestQuery {

    /**
     * 判断题目是否属于某个正在进行中的比赛（start_time &lt;= now &lt;= end_time）。
     *
     * @param problemId 题目 ID
     * @param now       判定时刻（由调用方传入，便于测试与缓存键对齐）
     * @return true = 该题正处于比赛时间窗内
     */
    boolean hasRunningContestForProblem(Long problemId, LocalDateTime now);
}
