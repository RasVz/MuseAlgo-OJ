package com.musealgo.oj.analytics.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.musealgo.oj.analytics.domain.MasteryCalculator;
import com.musealgo.oj.analytics.dto.PracticeAnalyticsVO;
import com.musealgo.oj.analytics.dto.PracticeAnalyticsVO.DifficultyItem;
import com.musealgo.oj.analytics.dto.PracticeAnalyticsVO.DimensionItem;
import com.musealgo.oj.analytics.dto.PracticeAnalyticsVO.Overview;
import com.musealgo.oj.analytics.dto.PracticeAnalyticsVO.SolvedProblemItem;
import com.musealgo.oj.analytics.dto.PracticeAnalyticsVO.TagItem;
import com.musealgo.oj.analytics.dto.PracticeAnalyticsVO.TriedProblemItem;
import com.musealgo.oj.analytics.entity.Tag;
import com.musealgo.oj.analytics.mapper.TagMapper;
import com.musealgo.oj.api.problem.ProblemDigest;
import com.musealgo.oj.api.problem.ProblemQuery;
import com.musealgo.oj.api.profile.ProfileQuery;
import com.musealgo.oj.api.profile.ProfileQuery.DifficultyCount;
import com.musealgo.oj.api.profile.ProfileQuery.DimensionSummary;
import com.musealgo.oj.api.profile.ProfileQuery.PracticeOverview;
import com.musealgo.oj.api.profile.ProfileQuery.ProblemAttemptRow;
import com.musealgo.oj.api.profile.TagStat;
import com.musealgo.oj.common.exception.ForbiddenException;
import com.musealgo.oj.common.utils.SecurityUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本人练习分析（T7）——只读聚合服务。
 * <p>
 * 数据链路：Controller → 本服务 → {@link ProfileQuery}（judge 模块实现，读 submission 表）
 * + {@link TagMapper}（标签字典）+ {@link ProblemQuery}（题目标题/难度）。
 * 统计口径（过滤条件、时间窗、firstTry、列表上限）由 ProfileQuery 契约负责，
 * 本服务只做「范围解析 → 编排取数 → 补齐题目信息 → 组装 DTO」。
 * </p>
 * <p>
 * 关键口径（与任务/前端契约一致，改动前先对齐）：
 * <ul>
 *   <li>只统计 status!=0 且 judge_phase!='FAILED' 的提交；比赛与 VP 提交都计入；</li>
 *   <li>range=all：无时间条件；range=30d：[今天-29 天 00:00, 明天 00:00)，左闭右开；</li>
 *   <li>AC 率是百分数 0-100（1 位小数），分母为纳入统计的提交数，无提交时为 0；</li>
 *   <li>列表上限 {@link #LIST_LIMIT}=50，由服务传给 SQL 的 LIMIT，不在内存里截断；</li>
 *   <li>只做本人：userId 一律来自登录态，接口不接收 userId 参数，避免越权查他人。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PracticeAnalyticsService {

    /** 统计范围：全部时间。 */
    public static final String RANGE_ALL = "all";
    /** 统计范围：最近 30 天（含今天，共 30 个自然日）。 */
    public static final String RANGE_30D = "30d";
    /** 列表上限：已通过 / 尝试过列表各最多 50 条。 */
    public static final int LIST_LIMIT = 50;
    /** 难度分桶下界（与 problem.difficulty 的 800-3000 约定一致）。 */
    public static final int DIFFICULTY_MIN = 800;
    /** 难度分桶上界。 */
    public static final int DIFFICULTY_MAX = 3000;
    /** 难度分桶步长。 */
    public static final int DIFFICULTY_STEP = 100;
    /** 题目被删除/查不到时的占位标题：宁可显示占位符也不能让整个接口 500。 */
    static final String UNKNOWN_PROBLEM_TITLE = "未知题目";

    private static final DimensionSummary ZERO_DIMENSION = new DimensionSummary(0, 0, 0, 0);
    private static final PracticeOverview ZERO_OVERVIEW = new PracticeOverview(0, 0, 0, 0, 0.0, 0);

    private final ProfileQuery profileQuery;
    private final TagMapper tagMapper;
    private final ProblemQuery problemQuery;

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /**
     * 构建当前登录学生的练习分析。
     * <p>未登录直接 403（与 WeaknessController 的鉴权写法一致），不做匿名降级。</p>
     *
     * @param range all 或 30d，null/空串按 all 处理；其他值抛 IllegalArgumentException（→ HTTP code 400）
     */
    public PracticeAnalyticsVO analyzeCurrentUser(String range) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            throw new ForbiddenException("请先登录");
        }
        return analyze(userId, range, LocalDate.now());
    }

    /**
     * 按指定「今天」构建练习分析（抽出 today 参数便于单测固定日期，避免依赖系统时钟）。
     */
    PracticeAnalyticsVO analyze(Long userId, String range, LocalDate today) {
        PracticeWindow window = resolveWindow(range, today);

        PracticeOverview overview = profileQuery.practiceOverview(userId, window.from(), window.to());
        if (overview == null) {
            overview = ZERO_OVERVIEW;
        }
        List<TagStat> tagStats = orEmpty(profileQuery.practiceByTag(userId, window.from(), window.to()));
        Map<String, DimensionSummary> dimensionStats = profileQuery.practiceByDimension(
                userId, window.from(), window.to());
        List<DifficultyCount> difficultyCounts = orEmpty(
                profileQuery.practiceDifficulty(userId, window.from(), window.to()));
        List<ProblemAttemptRow> solvedRows = orEmpty(profileQuery.practiceSolvedProblems(
                userId, window.from(), window.to(), LIST_LIMIT));
        List<ProblemAttemptRow> triedRows = orEmpty(profileQuery.practiceTriedProblems(
                userId, window.from(), window.to(), LIST_LIMIT));

        // 题目标题/难度属于 problem 模块，必须经 oj-api 的 ProblemQuery 批量补；
        // 两个列表的题目 id 合并成一次批量查询，避免 N+1。
        Map<Long, ProblemDigest> digests = loadDigests(solvedRows, triedRows);

        return new PracticeAnalyticsVO(
                window.range(),
                window.periodStart(),
                window.periodEnd(),
                LocalDateTime.now(),
                new Overview(
                        overview.totalSubmissions(),
                        overview.acceptedSubmissions(),
                        overview.solvedProblemCount(),
                        overview.attemptedProblemCount(),
                        overview.acRate(),
                        overview.activeDays()),
                buildDimensions(dimensionStats),
                buildTags(tagStats),
                buildDifficulty(difficultyCounts),
                toSolvedProblems(solvedRows, digests),
                toTriedProblems(triedRows, digests),
                LIST_LIMIT);
    }

    // ------------------------------------------------------------------
    // 时间窗
    // ------------------------------------------------------------------

    /**
     * 解析统计范围。
     * <p>
     * 范围语义（应用层算好再传给 SQL，SQL 层不用 NOW()，避免数据库时区/跨零点造成口径漂移）：
     * <ul>
     *   <li>all：from/to 都为 null，无时间条件，periodStart/periodEnd 为 null；</li>
     *   <li>30d：from=今天-29 天 00:00，to=明天 00:00（左闭右开），
     *       periodStart=今天-29 天，periodEnd=今天。</li>
     * </ul>
     * 为什么 to 是「明天 00:00」而不是「今天 23:59:59」：create_time 是 DATETIME(0)，
     * 若用闭区间会在最后一秒的分界上出现漏算/重算，半开区间 [from, to) 没有这个问题。
     * </p>
     */
    static PracticeWindow resolveWindow(String range, LocalDate today) {
        String normalized = range == null ? null : range.trim();
        if (normalized == null || normalized.isEmpty() || RANGE_ALL.equals(normalized)) {
            return new PracticeWindow(RANGE_ALL, null, null, null, null);
        }
        if (RANGE_30D.equals(normalized)) {
            LocalDate start = today.minusDays(29);
            return new PracticeWindow(
                    RANGE_30D,
                    start.atStartOfDay(),
                    today.plusDays(1).atStartOfDay(),
                    start,
                    today);
        }
        throw new IllegalArgumentException("range 仅支持 all 或 30d");
    }

    /** 解析后的统计窗口：range + SQL 用的半开区间 + 展示用的日期区间。 */
    record PracticeWindow(
            String range,
            LocalDateTime from,
            LocalDateTime to,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {}

    // ------------------------------------------------------------------
    // DTO 组装
    // ------------------------------------------------------------------

    /**
     * 知识域维度：以 tag 字典的根节点为基准（不随数据波动），无数据也返回 mastery=0，
     * mastery 复用画像的 {@link ProfileApplicationService#computeDimensionMastery} 公式。
     */
    private List<DimensionItem> buildDimensions(Map<String, DimensionSummary> dimensionStats) {
        List<Tag> dimensions = tagMapper.selectDimensions();
        if (dimensions == null || dimensions.isEmpty()) {
            return List.of();
        }
        Map<String, DimensionSummary> stats = dimensionStats != null ? dimensionStats : Map.of();
        List<DimensionItem> items = new ArrayList<>(dimensions.size());
        Set<String> seen = new HashSet<>();
        for (Tag dimension : dimensions) {
            String key = dimension.getDimension();
            if (key == null || !seen.add(key)) {
                continue;
            }
            DimensionSummary summary = stats.getOrDefault(key, ZERO_DIMENSION);
            items.add(new DimensionItem(
                    key,
                    ProfileApplicationService.DIMENSION_LABELS.getOrDefault(key, dimension.getName()),
                    summary.attempted(),
                    summary.passed(),
                    summary.firstTryPassed(),
                    ProfileApplicationService.computeDimensionMastery(summary)));
        }
        return items;
    }

    /**
     * 标签分布：只保留 attempted>0，按 attempted 降序（并列时依次按 passed 降序、tagId 升序稳定排序），
     * 最多 {@link #LIST_LIMIT} 条；mastery 用 {@link MasteryCalculator} 与画像口径一致。
     */
    private List<TagItem> buildTags(List<TagStat> tagStats) {
        Comparator<TagStat> order = Comparator
                .comparingInt(TagStat::attempted).reversed()
                .thenComparing(Comparator.comparingInt(TagStat::passed).reversed())
                .thenComparing(stat -> stat.tagId() == null ? Long.MAX_VALUE : stat.tagId());
        return tagStats.stream()
                .filter(stat -> stat != null && stat.attempted() > 0)
                .sorted(order)
                .limit(LIST_LIMIT)
                .map(stat -> new TagItem(
                        stat.tagId(),
                        stat.tagName(),
                        stat.dimension(),
                        stat.attempted(),
                        stat.passed(),
                        MasteryCalculator.mastery(stat)))
                .toList();
    }

    /**
     * 难度分桶：SQL 只返回有数据的桶，这里补齐 800..3000 全量 100 分桶（无数据填 0）。
     * <p>防御性处理：即使上游返回越界 rating，也先归一到合法桶再合并，保证输出桶号合法。</p>
     */
    private List<DifficultyItem> buildDifficulty(List<DifficultyCount> difficultyCounts) {
        Map<Integer, int[]> merged = new HashMap<>();
        for (DifficultyCount count : difficultyCounts) {
            if (count == null) {
                continue;
            }
            int[] bucket = merged.computeIfAbsent(bucketOf(count.rating()), key -> new int[2]);
            bucket[0] += count.solved();
            bucket[1] += count.attempted();
        }
        List<DifficultyItem> items = new ArrayList<>(
                (DIFFICULTY_MAX - DIFFICULTY_MIN) / DIFFICULTY_STEP + 1);
        for (int rating = DIFFICULTY_MIN; rating <= DIFFICULTY_MAX; rating += DIFFICULTY_STEP) {
            int[] bucket = merged.get(rating);
            items.add(new DifficultyItem(
                    rating,
                    bucket == null ? 0 : bucket[0],
                    bucket == null ? 0 : bucket[1]));
        }
        return items;
    }

    /** 把任意难度分归一到 800-3000 的 100 分档位（越界值夹到边界）。 */
    static int bucketOf(Integer difficulty) {
        if (difficulty == null) {
            return DIFFICULTY_MIN;
        }
        int bucket = (difficulty / DIFFICULTY_STEP) * DIFFICULTY_STEP;
        return Math.max(DIFFICULTY_MIN, Math.min(DIFFICULTY_MAX, bucket));
    }

    private List<SolvedProblemItem> toSolvedProblems(
            List<ProblemAttemptRow> rows, Map<Long, ProblemDigest> digests) {
        List<SolvedProblemItem> items = new ArrayList<>(rows.size());
        for (ProblemAttemptRow row : rows) {
            if (row == null || row.problemId() == null) {
                continue;
            }
            ProblemDigest digest = digests.get(row.problemId());
            items.add(new SolvedProblemItem(
                    row.problemId(),
                    titleOf(digest),
                    difficultyOf(digest),
                    row.occurredAt()));
        }
        return items;
    }

    private List<TriedProblemItem> toTriedProblems(
            List<ProblemAttemptRow> rows, Map<Long, ProblemDigest> digests) {
        List<TriedProblemItem> items = new ArrayList<>(rows.size());
        for (ProblemAttemptRow row : rows) {
            if (row == null || row.problemId() == null) {
                continue;
            }
            ProblemDigest digest = digests.get(row.problemId());
            items.add(new TriedProblemItem(
                    row.problemId(),
                    titleOf(digest),
                    difficultyOf(digest),
                    row.occurredAt()));
        }
        return items;
    }

    /**
     * 批量补齐题目摘要（标题/难度）。跨模块只读契约，不直连 problem 表。
     * <p>题目被删除/查不到时保持 null，由 titleOf 兜「未知题目」；
     * 批量查询本身失败也不阻断练习统计主流程（标题是增值信息），降级为全部占位标题。</p>
     */
    private Map<Long, ProblemDigest> loadDigests(
            List<ProblemAttemptRow> solvedRows, List<ProblemAttemptRow> triedRows) {
        Set<Long> problemIds = new LinkedHashSet<>();
        collectProblemIds(solvedRows, problemIds);
        collectProblemIds(triedRows, problemIds);
        if (problemIds.isEmpty()) {
            return Map.of();
        }
        try {
            List<ProblemDigest> digests = problemQuery.findDigestBatch(problemIds);
            if (digests == null || digests.isEmpty()) {
                return Map.of();
            }
            Map<Long, ProblemDigest> byId = new LinkedHashMap<>(digests.size());
            for (ProblemDigest digest : digests) {
                if (digest != null && digest.id() != null) {
                    byId.put(digest.id(), digest);
                }
            }
            return byId;
        } catch (RuntimeException e) {
            log.warn("练习分析批量查询题目摘要失败，降级为占位标题。problemIds={}: {}",
                    problemIds, e.getMessage());
            return Map.of();
        }
    }

    private static void collectProblemIds(List<ProblemAttemptRow> rows, Set<Long> problemIds) {
        for (ProblemAttemptRow row : rows) {
            if (row != null && row.problemId() != null) {
                problemIds.add(row.problemId());
            }
        }
    }

    private static String titleOf(ProblemDigest digest) {
        if (digest == null || digest.title() == null || digest.title().isBlank()) {
            return UNKNOWN_PROBLEM_TITLE;
        }
        return digest.title();
    }

    private static int difficultyOf(ProblemDigest digest) {
        return digest == null || digest.difficulty() == null ? 0 : digest.difficulty();
    }

    private static <T> List<T> orEmpty(List<T> rows) {
        return rows != null ? rows : List.of();
    }
}
