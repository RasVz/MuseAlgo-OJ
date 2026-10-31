package com.musealgo.oj.analytics.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import com.musealgo.oj.analytics.dto.PracticeAnalyticsVO;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * 练习分析服务单测（seam：PracticeAnalyticsService.analyze / resolveWindow，mock ProfileQuery）。
 * <p>
 * 覆盖验收要求：all/30d 窗口解析、DTO 映射、空数据零值结构、维度/标签 mastery 与 JSON 契约字段名。
 * 不覆盖 SQL 过滤口径（那部分由 oj-admin 的 PracticeAnalyticsIT 用固定 fixture 验证）。
 */
@ExtendWith(MockitoExtension.class)
class PracticeAnalyticsServiceTest {

    private static final Long USER_ID = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    @Mock
    private ProfileQuery profileQuery;
    @Mock
    private TagMapper tagMapper;
    @Mock
    private ProblemQuery problemQuery;

    private PracticeAnalyticsService newService() {
        return new PracticeAnalyticsService(profileQuery, tagMapper, problemQuery);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // 辅助构造
    // ------------------------------------------------------------------

    private static Tag tag(String dimension, String name) {
        Tag tag = new Tag();
        tag.setDimension(dimension);
        tag.setName(name);
        return tag;
    }

    /** 与 seed 数据一致的 5 个知识域（真库 V9 后是 6 维，这里只验证「按字典返回」不写死数量）。 */
    private static List<Tag> seededDimensions() {
        return List.of(
                tag("ALGORITHM", "算法"),
                tag("DATA_STRUCTURE", "数据结构"),
                tag("MATH", "数学"),
                tag("GRAPH", "图论"),
                tag("STRING", "字符串"));
    }

    private static TagStat tagStat(long tagId, String name, String dimension,
                                   int attempted, int passed, int firstTry, int maxDifficulty) {
        return new TagStat(tagId, name, null, dimension,
                attempted, passed, firstTry, attempted, 0.0, maxDifficulty);
    }

    private static PracticeOverview zeroOverview() {
        return new PracticeOverview(0, 0, 0, 0, 0.0, 0);
    }

    /** 每个 analyze 都会调用的 6 个契约方法 + 维度字典，按测试需要给默认值。 */
    private void stubEmptyAggregates() {
        when(profileQuery.practiceOverview(eq(USER_ID), any(), any())).thenReturn(zeroOverview());
        when(profileQuery.practiceByTag(eq(USER_ID), any(), any())).thenReturn(List.of());
        when(profileQuery.practiceByDimension(eq(USER_ID), any(), any())).thenReturn(Map.of());
        when(profileQuery.practiceDifficulty(eq(USER_ID), any(), any())).thenReturn(List.of());
        when(profileQuery.practiceSolvedProblems(eq(USER_ID), any(), any(), anyInt())).thenReturn(List.of());
        when(profileQuery.practiceTriedProblems(eq(USER_ID), any(), any(), anyInt())).thenReturn(List.of());
        when(tagMapper.selectDimensions()).thenReturn(seededDimensions());
    }

    // ------------------------------------------------------------------
    // 时间窗解析
    // ------------------------------------------------------------------

    @Test
    void allRangeHasNoTimeConditionAndNullPeriod() {
        PracticeAnalyticsService.PracticeWindow window =
                PracticeAnalyticsService.resolveWindow("all", TODAY);

        assertEquals(PracticeAnalyticsService.RANGE_ALL, window.range());
        assertNull(window.from());
        assertNull(window.to());
        assertNull(window.periodStart());
        assertNull(window.periodEnd());
    }

    @Test
    void nullOrBlankRangeDefaultsToAll() {
        assertEquals(PracticeAnalyticsService.RANGE_ALL,
                PracticeAnalyticsService.resolveWindow(null, TODAY).range());
        assertEquals(PracticeAnalyticsService.RANGE_ALL,
                PracticeAnalyticsService.resolveWindow("  ", TODAY).range());
    }

    @Test
    void thirtyDayRangeIsHalfOpenWindowOfThirtyCalendarDays() {
        PracticeAnalyticsService.PracticeWindow window =
                PracticeAnalyticsService.resolveWindow("30d", TODAY);

        assertEquals(PracticeAnalyticsService.RANGE_30D, window.range());
        // 今天-29 天 00:00 ～ 明天 00:00（左闭右开）；periodStart/periodEnd 是展示用日期
        assertEquals(TODAY.minusDays(29).atStartOfDay(), window.from());
        assertEquals(TODAY.plusDays(1).atStartOfDay(), window.to());
        assertEquals(TODAY.minusDays(29), window.periodStart());
        assertEquals(TODAY, window.periodEnd());
    }

    @Test
    void illegalRangeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PracticeAnalyticsService.resolveWindow("7d", TODAY));
    }

    @Test
    void analyzePassesWindowToEveryAggregate() {
        stubEmptyAggregates();

        PracticeAnalyticsVO vo = newService().analyze(USER_ID, "30d", TODAY);

        LocalDateTime from = TODAY.minusDays(29).atStartOfDay();
        LocalDateTime to = TODAY.plusDays(1).atStartOfDay();
        verify(profileQuery).practiceOverview(USER_ID, from, to);
        verify(profileQuery).practiceByTag(USER_ID, from, to);
        verify(profileQuery).practiceByDimension(USER_ID, from, to);
        verify(profileQuery).practiceDifficulty(USER_ID, from, to);
        verify(profileQuery).practiceSolvedProblems(USER_ID, from, to, PracticeAnalyticsService.LIST_LIMIT);
        verify(profileQuery).practiceTriedProblems(USER_ID, from, to, PracticeAnalyticsService.LIST_LIMIT);
        assertEquals("30d", vo.range());
        assertEquals(TODAY.minusDays(29), vo.periodStart());
        assertEquals(TODAY, vo.periodEnd());
        assertNotNull(vo.generatedAt());
    }

    @Test
    void allRangeQueryUsesNullBounds() {
        stubEmptyAggregates();

        PracticeAnalyticsVO vo = newService().analyze(USER_ID, "all", TODAY);

        verify(profileQuery).practiceOverview(USER_ID, null, null);
        verify(profileQuery).practiceSolvedProblems(USER_ID, null, null, PracticeAnalyticsService.LIST_LIMIT);
        assertEquals("all", vo.range());
        assertNull(vo.periodStart());
        assertNull(vo.periodEnd());
    }

    // ------------------------------------------------------------------
    // 鉴权
    // ------------------------------------------------------------------

    @Test
    void anonymousCallerIsForbidden() {
        SecurityContextHolder.clearContext();

        assertThrows(ForbiddenException.class, () -> newService().analyzeCurrentUser("all"));
    }

    // ------------------------------------------------------------------
    // 空数据零值结构
    // ------------------------------------------------------------------

    @Test
    void emptyDataReturnsZeroValueStructure() {
        stubEmptyAggregates();

        PracticeAnalyticsVO vo = newService().analyze(USER_ID, "all", TODAY);

        assertEquals(0, vo.overview().totalSubmissions());
        assertEquals(0, vo.overview().acceptedSubmissions());
        assertEquals(0, vo.overview().solvedProblemCount());
        assertEquals(0, vo.overview().attemptedProblemCount());
        assertEquals(0.0, vo.overview().acRate(), 0.0001);
        assertEquals(0, vo.overview().activeDays());

        // 维度仍返回整个 tag 字典，mastery=0
        assertEquals(5, vo.dimensions().size());
        assertEquals("ALGORITHM", vo.dimensions().get(0).key());
        assertEquals("算法", vo.dimensions().get(0).label());
        assertTrue(vo.dimensions().stream().allMatch(d -> d.mastery() == 0));
        assertTrue(vo.dimensions().stream().allMatch(d -> d.attempted() == 0 && d.passed() == 0));

        // 难度仍返回 800..3000 全量 100 分桶
        assertEquals(23, vo.difficulty().size());
        assertEquals(800, vo.difficulty().get(0).rating());
        assertEquals(3000, vo.difficulty().get(22).rating());
        assertTrue(vo.difficulty().stream().allMatch(d -> d.solved() == 0 && d.attempted() == 0));

        assertTrue(vo.tags().isEmpty());
        assertTrue(vo.solvedProblems().isEmpty());
        assertTrue(vo.triedProblems().isEmpty());
        assertEquals(50, vo.listLimit());
    }

    // ------------------------------------------------------------------
    // DTO 映射：总览 / 维度 / 标签 / 难度 / 题目列表
    // ------------------------------------------------------------------

    @Test
    void mapsOverviewAndDimensionMastery() {
        when(profileQuery.practiceOverview(eq(USER_ID), any(), any()))
                .thenReturn(new PracticeOverview(12, 6, 5, 8, 50.0, 2));
        when(profileQuery.practiceByTag(eq(USER_ID), any(), any())).thenReturn(List.of());
        when(profileQuery.practiceByDimension(eq(USER_ID), any(), any())).thenReturn(Map.of(
                "ALGORITHM", new DimensionSummary(4, 2, 1, 7),
                "DP", new DimensionSummary(2, 2, 2, 2)));
        when(profileQuery.practiceDifficulty(eq(USER_ID), any(), any())).thenReturn(List.of());
        when(profileQuery.practiceSolvedProblems(eq(USER_ID), any(), any(), anyInt())).thenReturn(List.of());
        when(profileQuery.practiceTriedProblems(eq(USER_ID), any(), any(), anyInt())).thenReturn(List.of());
        when(tagMapper.selectDimensions()).thenReturn(List.of(
                tag("ALGORITHM", "算法"),
                tag("DP", "动态规划"),
                tag("MATH", "数学")));

        PracticeAnalyticsVO vo = newService().analyze(USER_ID, "all", TODAY);

        assertEquals(12, vo.overview().totalSubmissions());
        assertEquals(6, vo.overview().acceptedSubmissions());
        assertEquals(5, vo.overview().solvedProblemCount());
        assertEquals(8, vo.overview().attemptedProblemCount());
        assertEquals(50.0, vo.overview().acRate(), 0.0001);
        assertEquals(2, vo.overview().activeDays());

        assertEquals(3, vo.dimensions().size());
        PracticeAnalyticsVO.DimensionItem algorithm = vo.dimensions().get(0);
        assertEquals("ALGORITHM", algorithm.key());
        assertEquals("算法", algorithm.label());
        assertEquals(4, algorithm.attempted());
        assertEquals(2, algorithm.passed());
        assertEquals(1, algorithm.firstTryPassed());
        // 维度 mastery 复用画像公式：100*(0.8*2/4 + 0.2*1/4) = 45
        assertEquals(45, algorithm.mastery());

        // DP 不在 DIMENSION_LABELS 里，label 回退 tag.name
        assertEquals("动态规划", vo.dimensions().get(1).label());
        assertEquals(100, vo.dimensions().get(1).mastery());
        assertEquals(0, vo.dimensions().get(2).mastery());
    }

    @Test
    void mapsTagsSortedByAttemptedAndRecomputesMastery() {
        TagStat perfect = tagStat(1L, "动态规划", "ALGORITHM", 2, 2, 2, 3000);
        TagStat failedOnly = tagStat(2L, "贪心", "ALGORITHM", 1, 0, 0, 800);
        TagStat untouched = tagStat(3L, "搜索", "ALGORITHM", 0, 0, 0, 0);
        TagStat moreAttempts = tagStat(4L, "数组", "DATA_STRUCTURE", 5, 3, 1, 1200);
        when(profileQuery.practiceOverview(eq(USER_ID), any(), any())).thenReturn(zeroOverview());
        when(profileQuery.practiceByTag(eq(USER_ID), any(), any()))
                .thenReturn(List.of(failedOnly, untouched, perfect, moreAttempts));
        when(profileQuery.practiceByDimension(eq(USER_ID), any(), any())).thenReturn(Map.of());
        when(profileQuery.practiceDifficulty(eq(USER_ID), any(), any())).thenReturn(List.of());
        when(profileQuery.practiceSolvedProblems(eq(USER_ID), any(), any(), anyInt())).thenReturn(List.of());
        when(profileQuery.practiceTriedProblems(eq(USER_ID), any(), any(), anyInt())).thenReturn(List.of());
        when(tagMapper.selectDimensions()).thenReturn(List.of());

        PracticeAnalyticsVO vo = newService().analyze(USER_ID, "all", TODAY);

        // attempted=0 的标签被过滤；按 attempted 降序
        assertEquals(3, vo.tags().size());
        assertEquals(4L, vo.tags().get(0).tagId());
        assertEquals(1L, vo.tags().get(1).tagId());
        assertEquals(2L, vo.tags().get(2).tagId());
        // 标签 mastery 用 MasteryCalculator：
        //   tag1 全 AC + 满难度 = 100；tag2 只有提交没有通过 = 10；
        //   tag4 attempted=5/passed=3/firstTry=1/maxDiff=1200 ≈ 43（0.4*0.6 + 0.3*0.18 + 0.2*0.2 + 0.1）
        assertEquals(100, vo.tags().get(1).mastery());
        assertEquals(10, vo.tags().get(2).mastery());
        assertEquals(43, vo.tags().get(0).mastery());
        assertEquals("ALGORITHM", vo.tags().get(1).dimension());
    }

    @Test
    void tagsAreLimitedToFifty() {
        List<TagStat> many = new ArrayList<>();
        for (int i = 1; i <= 51; i++) {
            many.add(tagStat(i, "tag-" + i, "ALGORITHM", i, 1, 1, 800));
        }
        when(profileQuery.practiceOverview(eq(USER_ID), any(), any())).thenReturn(zeroOverview());
        when(profileQuery.practiceByTag(eq(USER_ID), any(), any())).thenReturn(many);
        when(profileQuery.practiceByDimension(eq(USER_ID), any(), any())).thenReturn(Map.of());
        when(profileQuery.practiceDifficulty(eq(USER_ID), any(), any())).thenReturn(List.of());
        when(profileQuery.practiceSolvedProblems(eq(USER_ID), any(), any(), anyInt())).thenReturn(List.of());
        when(profileQuery.practiceTriedProblems(eq(USER_ID), any(), any(), anyInt())).thenReturn(List.of());
        when(tagMapper.selectDimensions()).thenReturn(List.of());

        PracticeAnalyticsVO vo = newService().analyze(USER_ID, "all", TODAY);

        assertEquals(50, vo.tags().size());
        assertEquals(51L, vo.tags().get(0).tagId()); // attempted 最大的排最前
        assertEquals(2L, vo.tags().get(49).tagId());
    }

    @Test
    void mapsDifficultyBucketsAndFillsFullRange() {
        when(profileQuery.practiceOverview(eq(USER_ID), any(), any())).thenReturn(zeroOverview());
        when(profileQuery.practiceByTag(eq(USER_ID), any(), any())).thenReturn(List.of());
        when(profileQuery.practiceByDimension(eq(USER_ID), any(), any())).thenReturn(Map.of());
        when(profileQuery.practiceDifficulty(eq(USER_ID), any(), any())).thenReturn(List.of(
                new DifficultyCount(800, 2, 3),
                new DifficultyCount(1200, 1, 1)));
        when(profileQuery.practiceSolvedProblems(eq(USER_ID), any(), any(), anyInt())).thenReturn(List.of());
        when(profileQuery.practiceTriedProblems(eq(USER_ID), any(), any(), anyInt())).thenReturn(List.of());
        when(tagMapper.selectDimensions()).thenReturn(List.of());

        PracticeAnalyticsVO vo = newService().analyze(USER_ID, "all", TODAY);

        assertEquals(23, vo.difficulty().size());
        PracticeAnalyticsVO.DifficultyItem first = vo.difficulty().get(0);
        assertEquals(800, first.rating());
        assertEquals(2, first.solved());
        assertEquals(3, first.attempted());
        PracticeAnalyticsVO.DifficultyItem five = vo.difficulty().get(4);
        assertEquals(1200, five.rating());
        assertEquals(1, five.solved());
        // 其余桶补 0
        assertEquals(0, vo.difficulty().get(1).attempted());
        assertEquals(0, vo.difficulty().get(22).attempted());
    }

    @Test
    void mapsSolvedAndTriedProblemsWithDigestTitles() {
        LocalDateTime solvedAt = LocalDateTime.of(2026, 10, 19, 12, 0);
        LocalDateTime lastAttemptAt = LocalDateTime.of(2026, 10, 20, 9, 30);
        when(profileQuery.practiceOverview(eq(USER_ID), any(), any())).thenReturn(zeroOverview());
        when(profileQuery.practiceByTag(eq(USER_ID), any(), any())).thenReturn(List.of());
        when(profileQuery.practiceByDimension(eq(USER_ID), any(), any())).thenReturn(Map.of());
        when(profileQuery.practiceDifficulty(eq(USER_ID), any(), any())).thenReturn(List.of());
        when(profileQuery.practiceSolvedProblems(eq(USER_ID), any(), any(), anyInt()))
                .thenReturn(List.of(new ProblemAttemptRow(101L, solvedAt)));
        when(profileQuery.practiceTriedProblems(eq(USER_ID), any(), any(), anyInt()))
                .thenReturn(List.of(new ProblemAttemptRow(202L, lastAttemptAt)));
        // 101 查得到，202 已删除/查不到 → 占位标题
        when(problemQuery.findDigestBatch(any())).thenReturn(List.of(
                new ProblemDigest(101L, "两数之和", 800, true)));
        when(tagMapper.selectDimensions()).thenReturn(List.of());

        PracticeAnalyticsVO vo = newService().analyze(USER_ID, "all", TODAY);

        assertEquals(1, vo.solvedProblems().size());
        PracticeAnalyticsVO.SolvedProblemItem solved = vo.solvedProblems().get(0);
        assertEquals(101L, solved.problemId());
        assertEquals("两数之和", solved.title());
        assertEquals(800, solved.difficulty());
        assertEquals(solvedAt, solved.solvedAt());

        assertEquals(1, vo.triedProblems().size());
        PracticeAnalyticsVO.TriedProblemItem tried = vo.triedProblems().get(0);
        assertEquals(202L, tried.problemId());
        assertEquals("未知题目", tried.title());
        assertEquals(0, tried.difficulty());
        assertEquals(lastAttemptAt, tried.lastAttemptAt());
    }

    @Test
    void digestFailureDegradesToUnknownTitleInsteadOfFailing() {
        when(profileQuery.practiceOverview(eq(USER_ID), any(), any())).thenReturn(zeroOverview());
        when(profileQuery.practiceByTag(eq(USER_ID), any(), any())).thenReturn(List.of());
        when(profileQuery.practiceByDimension(eq(USER_ID), any(), any())).thenReturn(Map.of());
        when(profileQuery.practiceDifficulty(eq(USER_ID), any(), any())).thenReturn(List.of());
        when(profileQuery.practiceSolvedProblems(eq(USER_ID), any(), any(), anyInt()))
                .thenReturn(List.of(new ProblemAttemptRow(101L, LocalDateTime.of(2026, 10, 19, 8, 0))));
        when(profileQuery.practiceTriedProblems(eq(USER_ID), any(), any(), anyInt())).thenReturn(List.of());
        when(problemQuery.findDigestBatch(any())).thenThrow(new RuntimeException("problem 模块不可用"));
        when(tagMapper.selectDimensions()).thenReturn(List.of());

        PracticeAnalyticsVO vo = newService().analyze(USER_ID, "all", TODAY);

        assertEquals("未知题目", vo.solvedProblems().get(0).title());
    }

    // ------------------------------------------------------------------
    // JSON 契约（字段名固定，前端已按此实现）
    // ------------------------------------------------------------------

    @Test
    void jsonFieldNamesMatchFrozenContract() throws Exception {
        stubEmptyAggregates();
        PracticeAnalyticsVO vo = newService().analyze(USER_ID, "30d", TODAY);

        // 与 Spring Boot 默认一致：日期不写成时间戳数组，LocalDate 输出 yyyy-MM-dd
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        JsonNode root = mapper.readTree(mapper.writeValueAsString(vo));

        List<String> topLevel = new ArrayList<>();
        root.fieldNames().forEachRemaining(topLevel::add);
        assertEquals(List.of("range", "periodStart", "periodEnd", "generatedAt", "overview",
                        "dimensions", "tags", "difficulty", "solvedProblems", "triedProblems", "listLimit"),
                topLevel);

        assertEquals("30d", root.get("range").asText());
        assertEquals(TODAY.minusDays(29).toString(), root.get("periodStart").asText());
        assertEquals(TODAY.toString(), root.get("periodEnd").asText());
        // 契约示例是 ISO-8601 带 T 的写法，与全局空格格式不同，字段级 @JsonFormat 覆盖
        assertTrue(root.get("generatedAt").asText().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"));

        List<String> overviewFields = new ArrayList<>();
        root.get("overview").fieldNames().forEachRemaining(overviewFields::add);
        assertEquals(List.of("totalSubmissions", "acceptedSubmissions", "solvedProblemCount",
                "attemptedProblemCount", "acRate", "activeDays"), overviewFields);
        assertEquals(0.0, root.get("overview").get("acRate").asDouble(), 0.0001);
        assertEquals(50, root.get("listLimit").asInt());

        List<String> dimensionFields = new ArrayList<>();
        root.get("dimensions").get(0).fieldNames().forEachRemaining(dimensionFields::add);
        assertEquals(List.of("key", "label", "attempted", "passed", "firstTryPassed", "mastery"),
                dimensionFields);
        assertEquals(23, root.get("difficulty").size());
        List<String> difficultyFields = new ArrayList<>();
        root.get("difficulty").get(0).fieldNames().forEachRemaining(difficultyFields::add);
        assertEquals(List.of("rating", "solved", "attempted"), difficultyFields);
    }
}
