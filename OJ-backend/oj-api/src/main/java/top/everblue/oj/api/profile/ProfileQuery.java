package top.everblue.oj.api.profile;

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
}