package com.musealgo.oj.api.user;

/**
 * 用户计数写契约 — 跨模块修改粉丝/关注/提交/通过题数的唯一入口。
 * <p>
 * 实现由 oj-system 模块提供（数据归属方），oj-community、oj-judge 等模块禁止直接注入 UserService。
 * </p>
 * <p>
 * 提交计数由提交与判题链路增量维护，与 {@code user.submit_count/accepted_count} 冗余列一一对应；
 * 历史值由 Flyway V55 回填。T8 受控重判改变提交结果后必须重新按 submission 全量重算，
 * 不能只做增量，否则这两列会与真实流水漂移。
 * </p>
 */
public interface UserStatsCommand {

    /** 增减粉丝数（delta 为 +1 或 -1）。 */
    void incrementFollowerCount(Long userId, int delta);

    /** 增减关注数（delta 为 +1 或 -1）。 */
    void incrementFollowingCount(Long userId, int delta);

    /** 新增一条提交后递增总提交数（submit_count，含待判与失败提交）。 */
    void incrementSubmissionCount(Long userId);

    /** 用户首次 AC 某道题后递增通过题目数（accepted_count，按不同题目去重）。 */
    void incrementAcceptedProblemCount(Long userId);

    /**
     * 按 submission 表全量重算某用户的 submit_count/accepted_count。
     * <p>T8 重判改变提交结果后使用；与 V55 回填同一口径，幂等。</p>
     */
    void recalculateCounters(Long userId);
}
