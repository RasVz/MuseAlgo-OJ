package com.musealgo.oj.api.contest;

import java.util.Optional;
import java.util.Set;

/**
 * 比赛时间窗只读查询契约 — 提交访问校验/比赛状态判断用。
 * <p>
 * 实现由 oj-contest 模块提供，oj-judge 等模块通过此契约判断
 * 提交是否处于比赛进行中，禁止直接注入 ContestService/Mapper。
 * </p>
 */
public interface ContestScheduleQuery {

    /**
     * 按比赛 ID 查询时间窗基础信息。
     *
     * @param contestId 比赛 ID
     * @return 比赛时间窗（不存在返回空）
     */
    Optional<ContestSchedule> findById(Long contestId);

    /** 该教师创建的未删除比赛 ID（用于跨模块访问范围过滤）。 */
    java.util.List<Long> findOwnedIds(Long teacherId);

    /**
     * 查询当前正在进行的 OI 模式比赛 ID 集合。
     *
     * @return 进行中 OI 比赛 ID 集合
     */
    Set<Long> findRunningOiContestIds();

    /**
     * 虚拟赛（VP）会话是否仍在进行。
     *
     * @param vpAttemptId VP 会话 ID
     * @return true = 会话进行中
     */
    boolean isVpAttemptRunning(Long vpAttemptId);

    /**
     * VP 会话是否已交卷并冻结成绩。
     * <p>T8-2b：只有 FINISHED 的 VP 会话允许重判提交；重判完成后按当前提交重算 VP 成绩。</p>
     *
     * @param vpAttemptId VP 会话 ID
     * @return true = 已 FINISHED
     */
    boolean isVpAttemptFinished(Long vpAttemptId);

    /**
     * 查询当前正在进行的 OI 模式 VP 会话 ID 集合。
     *
     * @return 进行中 OI VP 会话 ID 集合
     */
    Set<Long> findRunningOiVpAttemptIds();

    /**
     * 比赛是否已完成最终成绩/Rating 固化。
     * <p>T8 受控重判的 A 冻结策略依据：已固化比赛的提交默认禁止重判。</p>
     *
     * @param contestId 比赛 ID
     * @return true = 已固化（rank_finalized_at 非空）
     */
    boolean isRankFinalized(Long contestId);

    /**
     * 查询「已结束且未执行赛后查重」的比赛 ID 集合（查重 M2 自动触发用）。
     * <p>
     * 语义：end_time 已过且距结束至少 {@code graceMinutes} 分钟（等提交稳定），
     * 且未逻辑删除、plagiarism_checked = 0。
     * </p>
     *
     * @param graceMinutes 结束后等待分钟数
     * @return 待自动查重的比赛 ID 集合
     */
    Set<Long> findEndedUncheckedContestIds(int graceMinutes);
}
