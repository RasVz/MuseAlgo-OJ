package com.musealgo.oj.analytics.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 本人练习分析响应（{@code GET /profile/practice/me}）。
 * <p>
 * 字段名是与前端冻结的契约（T7），<strong>不要改名</strong>：前端 PracticeView 直接按这些 key 渲染，
 * 改名等于接口不兼容。新增字段只能追加，不能重命名/删除。
 * </p>
 * <p>
 * 数据来源全部是 {@link com.musealgo.oj.api.profile.ProfileQuery} 的只读聚合（judge 模块实现），
 * 本 DTO 只负责「聚合结果 → 前端结构」的映射，不含任何 SQL 或实体。
 * </p>
 * <p>
 * 时间格式：本接口的输出字段（generatedAt/solvedAt/lastAttemptAt）显式使用 ISO-8601 的
 * {@code yyyy-MM-dd'T'HH:mm:ss}。全局 JacksonConfig 默认把 LocalDateTime 输出成空格分隔格式，
 * 但本接口契约示例给的是 ISO 带 T 的写法，故在字段上用 {@code @JsonFormat} 覆盖；
 * 前端把这三个字段当字符串展示/交给 dayjs 解析均可。
 * </p>
 */
public record PracticeAnalyticsVO(
        /** 统计范围：all=全部时间，30d=最近 30 天 */
        String range,
        /** 30d 时为窗口起始日（今天-29 天），all 时为 null */
        LocalDate periodStart,
        /** 30d 时为今天，all 时为 null */
        LocalDate periodEnd,
        /** 本次统计生成时间 */
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime generatedAt,
        /** 练习总览 */
        Overview overview,
        /** 知识域维度（tag 字典全量，无数据也返回 mastery=0） */
        List<DimensionItem> dimensions,
        /** 标签分布（attempted>0，按 attempted 降序，最多 listLimit 条） */
        List<TagItem> tags,
        /** 难度分桶（800-3000 全量 100 分桶） */
        List<DifficultyItem> difficulty,
        /** 最近通过的题目（按 solvedAt 倒序，最多 listLimit 条） */
        List<SolvedProblemItem> solvedProblems,
        /** 尝试过但未通过的题目（按 lastAttemptAt 倒序，最多 listLimit 条） */
        List<TriedProblemItem> triedProblems,
        /** 列表条数上限（固定 50，前端据此显示「最近 N 条」） */
        int listLimit
) {

    /**
     * 总览指标。
     *
     * @param acRate 百分数 0-100（1 位小数），无已判提交时为 0
     */
    public record Overview(
            int totalSubmissions,
            int acceptedSubmissions,
            int solvedProblemCount,
            int attemptedProblemCount,
            double acRate,
            int activeDays
    ) {}

    /** 知识域掌握度（雷达图）。 */
    public record DimensionItem(
            String key,
            String label,
            int attempted,
            int passed,
            int firstTryPassed,
            int mastery
    ) {}

    /** 标签维度掌握度（标签分布）。 */
    public record TagItem(
            Long tagId,
            String tagName,
            String dimension,
            int attempted,
            int passed,
            int mastery
    ) {}

    /** 难度分桶。 */
    public record DifficultyItem(int rating, int solved, int attempted) {}

    /** 已通过题目。 */
    public record SolvedProblemItem(
            Long problemId,
            String title,
            int difficulty,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime solvedAt
    ) {}

    /** 尝试过但未通过的题目。 */
    public record TriedProblemItem(
            Long problemId,
            String title,
            int difficulty,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime lastAttemptAt
    ) {}
}
