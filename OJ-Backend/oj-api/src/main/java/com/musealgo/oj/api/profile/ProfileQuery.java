package com.musealgo.oj.api.profile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 学生画像查询契约 — 跨模块只读访问提交统计数据的唯一入口。
 * <p>
 * 实现由 oj-judge 模块提供（因为数据源是 submission 表），
 * oj-analytics 通过此契约消费数据，不直接注入 SubmissionMapper。
 */
public interface ProfileQuery {

    /**
     * 学生在 [from, to] 时间窗内按 tag 维度的聚合统计。
     * <p>
     * SQL 核心逻辑：submission JOIN problem_tag JOIN tag，GROUP BY tag。
     */
    List<TagStat> aggregateByTag(Long userId, LocalDateTime from, LocalDateTime to);

    /**
     * 按知识域（dimension）上卷汇总。
     * 返回 dimension → (attempted, passed, firstTryPassed, totalSubmissions) 的映射。
     */
    Map<String, DimensionSummary> aggregateByDimension(Long userId, LocalDateTime from, LocalDateTime to);

    /**
     * 学生的周概览统计：提交数、AC 数、活跃天数。
     */
    WeekOverview weekOverview(Long userId, LocalDateTime from, LocalDateTime to);

    /**
     * 学生历史所有 AC 过的题目 id（用于推荐题单时排除已掌握的题）。
     */
    java.util.Set<Long> findSolvedProblemIds(Long userId);

    /**
     * 批量聚合多个学生在各知识域的统计（班级百分位计算用，一条 SQL 避免 N+1）。
     *
     * @return userId → (dimension → DimensionSummary)
     */
    java.util.Map<Long, java.util.Map<String, DimensionSummary>> aggregateClassByDimension(
            java.util.Collection<Long> studentIds, LocalDateTime from, LocalDateTime to);

    /**
     * 知识域级摘要。
     */
    record DimensionSummary(int attempted, int passed, int firstTryPassed, int totalSubmissions) {}

    /**
     * 周概览。
     */
    record WeekOverview(
            int totalSubmissions,
            int acceptedCount,
            int acceptedProblemCount,  // AC 的不同题目数（去重）
            int activeDays             // 有提交的天数
    ) {}

    // ==================================================================
    // 练习分析（T7）—— 本人练习数据的只读聚合契约
    // ==================================================================
    // 与上面「画像/周报」旧方法刻意分开，因为两者口径不同：
    //   旧方法（aggregateByTag/weekOverview…）面向周报/推荐等场景，各自 SQL 的过滤条件不同，
    //   且都不区分待判/FAILED/已删除题目；
    //   练习分析只计入 status!=0 且 judge_phase!='FAILED' 的提交、JOIN 未删除题目，
    //   并明确计入比赛（contest_id 非空）与 VP（vp_attempt_id 非空）提交。
    // 新方法不修改旧方法签名与返回值，旧报告/快照链路不受影响。
    // 时间窗统一为半开区间 [from, to)；from/to 同时为 null 表示「全部时间」。
    // ==================================================================

    /**
     * 练习总览：统计窗口内符合条件的提交数、AC 数、AC 题数、尝试题数、AC 率与活跃天数。
     * <p>acRate 为百分数 0-100，保留 1 位小数；无已判提交时为 0。</p>
     */
    PracticeOverview practiceOverview(Long userId, LocalDateTime from, LocalDateTime to);

    /**
     * 练习分析：按 tag 聚合的尝试/通过/一次通过题数与最高难度。
     * <p>过滤口径同 {@link #practiceOverview}；是否包含根标签由 SQL 决定，与旧画像保持一致。</p>
     */
    List<TagStat> practiceByTag(Long userId, LocalDateTime from, LocalDateTime to);

    /**
     * 练习分析：按知识域上卷，返回 dimension → 摘要。
     * <p>只上卷有 parent_id 的叶子/中间标签，避免根维度标签重复计数。</p>
     */
    Map<String, DimensionSummary> practiceByDimension(Long userId, LocalDateTime from, LocalDateTime to);

    /**
     * 练习分析：按难度分桶（800-3000，100 一档）的通过题数与尝试题数。
     * <p>只返回有数据的桶；调用方负责补齐 800..3000 全量分桶。</p>
     */
    List<DifficultyCount> practiceDifficulty(Long userId, LocalDateTime from, LocalDateTime to);

    /**
     * 练习分析：窗口内已通过的题目（每题一条，按首次 AC 时间倒序），最多 {@code limit} 条。
     * <p>只返回 problemId 与时间，标题/难度由调用方经 oj-api 的 ProblemQuery 批量补齐。</p>
     */
    List<ProblemAttemptRow> practiceSolvedProblems(
            Long userId, LocalDateTime from, LocalDateTime to, int limit);

    /**
     * 练习分析：窗口内尝试过但窗口内没有 AC 的题目（每题一条，按最后尝试时间倒序）。
     */
    List<ProblemAttemptRow> practiceTriedProblems(
            Long userId, LocalDateTime from, LocalDateTime to, int limit);

    /**
     * 练习总览聚合结果。
     *
     * @param totalSubmissions     窗口内纳入统计的提交总数（不含待判/基础设施失败）
     * @param acceptedSubmissions  status=1 的提交数（同一题多次 AC 计多次）
     * @param solvedProblemCount   有 AC 的不同题目数
     * @param attemptedProblemCount 尝试过的不同题目数
     * @param acRate               AC 率百分数 0-100（1 位小数），无提交时 0
     * @param activeDays           有提交的自然日数
     */
    record PracticeOverview(
            int totalSubmissions,
            int acceptedSubmissions,
            int solvedProblemCount,
            int attemptedProblemCount,
            double acRate,
            int activeDays
    ) {}

    /**
     * 难度分桶计数。
     *
     * @param rating    难度桶（800-3000，100 一档）
     * @param solved    该桶内 AC 的不同题目数
     * @param attempted 该桶内尝试过的不同题目数
     */
    record DifficultyCount(int rating, int solved, int attempted) {}

    /**
     * 题目尝试摘要（已通过 / 尝试过列表用）。
     * <p>不携带标题与难度：跨模块「题目」数据必须经 {@code ProblemQuery} 读取，
     * 判题模块只负责从提交数据里聚合出 problemId 与时间。</p>
     *
     * @param problemId  题目 ID
     * @param occurredAt 已通过列表为首次 AC 时间；尝试列表为最后一次提交时间
     */
    record ProblemAttemptRow(Long problemId, LocalDateTime occurredAt) {}
}