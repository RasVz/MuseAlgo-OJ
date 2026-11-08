package com.musealgo.oj.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.musealgo.oj.api.profile.ProfileQuery;
import com.musealgo.oj.api.profile.ProfileQuery.DifficultyCount;
import com.musealgo.oj.api.profile.ProfileQuery.DimensionSummary;
import com.musealgo.oj.api.profile.ProfileQuery.PracticeOverview;
import com.musealgo.oj.api.profile.ProfileQuery.ProblemAttemptRow;
import com.musealgo.oj.api.profile.TagStat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T7 练习分析固定样本验收（真实 MySQL + MyBatis，直接校验 ProfileQuery 契约的统计口径）。
 * <p>
 * 覆盖任务要求的全部分支：比赛提交、VP 提交、待判（status=0）、基础设施失败（judge_phase=FAILED）、
 * 已删除题目（is_deleted=1）、30 天窗口过滤（40 天前的提交）、firstTryPassed、难度分桶、
 * 已通过/尝试列表的排序与 LIMIT；以及只统计本人数据（所有查询按 userId 过滤）。
 * </p>
 * <p>
 * fixture 全部用 JdbcTemplate 显式造数据并在 @AfterEach 清理，不依赖 seed.sql 的提交数据；
 * 只依赖 Flyway 种子里的 tag 维度根节点（ALGORITHM），否则知识域上卷无法构造。
 * </p>
 * <p>
 * 统计口径（与 PracticeAnalyticsService 契约一致）：
 * <ul>
 *   <li>纳入：status != 0 且 judge_phase != 'FAILED'，JOIN problem 且 is_deleted=0；</li>
 *   <li>比赛/VP 提交都计入（不按 contest_id / vp_attempt_id 过滤）；</li>
 *   <li>firstTryPassed：该题「最早一条被统计提交」是 AC（create_time, id 稳定排序）；</li>
 *   <li>30d 窗口 = [今天-29 天 00:00, 明天 00:00)，左闭右开；</li>
 *   <li>已通过列表 solvedAt=首次 AC 时间，尝试列表只含窗口内无 AC 的题，均按时间倒序、LIMIT 生效。</li>
 * </ul>
 * </p>
 */
@SpringBootTest(properties = {"oj.messaging.rabbitmq-enabled=false"})
class PracticeAnalyticsIT {

    private static final long CONTEST_ID = 990001L;      // submission.contest_id 无外键，仅作「比赛提交」标记
    private static final long VP_ATTEMPT_ID = 990002L;   // submission.vp_attempt_id 无外键，仅作「VP 提交」标记

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProfileQuery profileQuery;

    /** 每个测试方法创建一次实例，today 在实例初始化时固定；窗口内提交都落在今天，跨零点重跑也仍命中。 */
    private final LocalDate today = LocalDate.now();
    private final LocalDateTime todayStart = LocalDate.now().atStartOfDay();
    private final List<Long> problemIds = new ArrayList<>();
    private final List<Long> tagIds = new ArrayList<>();
    private Long userId;
    private Long otherUserId;

    // fixture 里的题目 id（在 setUp 中赋值）
    private Long p1;
    private Long p2;
    private Long p3;
    private Long p4;
    private Long p5;
    private Long p6;
    private Long p7;
    private Long p8;
    private Long p9;
    private Long p11;
    private Long pDeleted;
    private Long tagP1P2;
    private Long tagP8;

    @BeforeEach
    void fixture() {
        userId = insertUser();

        // 题目难度覆盖多个 100 分桶：800/1250(→1200)/1400/1500/1600/1000/1100/1300
        p1 = insertProblem(800, 0);
        p2 = insertProblem(1250, 0);   // 验证 100 分桶：1250 归入 1200
        p3 = insertProblem(1800, 0);   // 待判（不计入）
        p4 = insertProblem(1900, 0);   // judge_phase=FAILED（不计入）
        p5 = insertProblem(1000, 0);   // 比赛 AC
        p6 = insertProblem(1100, 0);   // VP AC
        p7 = insertProblem(1300, 0);   // 40 天前 AC（30d 窗口外）
        p8 = insertProblem(1400, 0);   // 先 WA 后 AC：firstTryPassed=0
        p9 = insertProblem(1500, 0);   // 只有 WA：进 triedProblems
        p11 = insertProblem(1600, 0);  // 只有 WA：进 triedProblems（比 p9 更晚，排序在前）
        pDeleted = insertProblem(1700, 1); // is_deleted=1：整题排除

        // 标签：两个叶子标签挂到 ALGORITHM 知识域下，用于 tag/维度聚合断言
        Long algorithmDimensionId = insertTags();
        tagP1P2 = insertTag("t7_it_tag_a_" + UUID.randomUUID(), algorithmDimensionId);
        tagP8 = insertTag("t7_it_tag_b_" + UUID.randomUUID(), algorithmDimensionId);
        linkProblemTag(p1, tagP1P2);
        linkProblemTag(p2, tagP1P2);
        linkProblemTag(p8, tagP8);

        LocalDateTime base = todayStart.plusHours(10); // 今天 10:00 起，全部落在今天（30d 窗口内）
        // p1：先 AC 再 WA —— later WA 不改变 firstTryPassed（最早一条是 AC）
        submit(p1, 1, "FINISHED", base, null, null);
        submit(p1, 2, "FINISHED", base.plusMinutes(5), null, null);
        // p2：先 AC 再 WA
        submit(p2, 1, "FINISHED", base.plusMinutes(10), null, null);
        submit(p2, 2, "FINISHED", base.plusMinutes(15), null, null);
        // p5：比赛提交（contest_id 非空，必须计入）
        submit(p5, 1, "FINISHED", base.plusMinutes(20), CONTEST_ID, null);
        // p6：VP 提交（vp_attempt_id 非空，必须计入）
        submit(p6, 1, "FINISHED", base.plusMinutes(25), null, VP_ATTEMPT_ID);
        // p8：先 WA 再 AC —— firstTryPassed=0，但最终 passed
        submit(p8, 2, "FINISHED", base.plusMinutes(30), null, null);
        submit(p8, 1, "FINISHED", base.plusMinutes(35), null, null);
        // p9 / p11：只有 WA —— 进 triedProblems，按最后提交时间倒序 p11 在 p9 前
        submit(p9, 2, "FINISHED", base.plusMinutes(40), null, null);
        submit(p11, 2, "FINISHED", base.plusMinutes(45), null, null);
        // p3 待判、p4 基础设施失败：都不计入任何统计
        submit(p3, 0, "QUEUED", base.plusMinutes(50), null, null);
        submit(p4, 0, "FAILED", base.plusMinutes(55), null, null);
        // 已删除题目的 AC：整题排除（若漏排除，总提交数/AC 题数会各多 1）
        submit(pDeleted, 1, "FINISHED", base.plusMinutes(60), null, null);

        // 窗口外：40 天前 p7 先 WA 后 AC（同一天，all 的 activeDays=2，30d 完全排除该题）
        LocalDateTime old = todayStart.minusDays(40).plusHours(8);
        submit(p7, 2, "FINISHED", old, null, null);
        submit(p7, 1, "FINISHED", old.plusHours(2), null, null);

        // 另一个用户的数据：证明统计只按 userId 过滤，不会串到别人
        otherUserId = insertUser();
        submitFor(otherUserId, p1, 1, "FINISHED", base);
    }

    @AfterEach
    void cleanup() {
        for (Long id : new Long[]{userId, otherUserId}) {
            if (id != null) {
                jdbc.update("DELETE FROM submission WHERE user_id = ?", id);
            }
        }
        for (Long problemId : problemIds) {
            jdbc.update("DELETE FROM problem_tag WHERE problem_id = ?", problemId);
            jdbc.update("DELETE FROM problem WHERE id = ?", problemId);
        }
        for (Long tagId : tagIds) {
            jdbc.update("DELETE FROM tag WHERE id = ?", tagId);
        }
        for (Long id : new Long[]{userId, otherUserId}) {
            if (id != null) {
                jdbc.update("DELETE FROM user WHERE id = ?", id);
            }
        }
        problemIds.clear();
        tagIds.clear();
        userId = null;
        otherUserId = null;
    }

    // ------------------------------------------------------------------
    // 总览：过滤口径 + 字段精确值
    // ------------------------------------------------------------------

    @Test
    void allRangeOverviewCountsOnlyJudgedSubmissionsOfThisUser() {
        PracticeOverview overview = profileQuery.practiceOverview(userId, null, null);

        // 计入 12 条：p1×2 + p2×2 + p5 + p6 + p8×2 + p9 + p11 + p7×2（比赛/VP/窗口外都算）
        // 排除 3 条：p3 待判、p4 FAILED、pDeleted 题目已删除
        assertEquals(12, overview.totalSubmissions());
        assertEquals(6, overview.acceptedSubmissions());   // p1 p2 p5 p6 p8 p7
        assertEquals(6, overview.solvedProblemCount());
        assertEquals(8, overview.attemptedProblemCount()); // + p9 p11
        assertEquals(50.0, overview.acRate(), 0.001);      // 6/12
        assertEquals(2, overview.activeDays());            // 今天 + 40 天前
    }

    @Test
    void thirtyDayWindowExcludesOlderSubmissions() {
        LocalDateTime from = today.minusDays(29).atStartOfDay();
        LocalDateTime to = today.plusDays(1).atStartOfDay();

        PracticeOverview overview = profileQuery.practiceOverview(userId, from, to);

        // 40 天前的 p7 两条提交被窗口排除；其余 10 条不变
        assertEquals(10, overview.totalSubmissions());
        assertEquals(5, overview.acceptedSubmissions());
        assertEquals(5, overview.solvedProblemCount());
        assertEquals(7, overview.attemptedProblemCount());
        assertEquals(50.0, overview.acRate(), 0.001);      // 5/10
        assertEquals(1, overview.activeDays());
    }

    // ------------------------------------------------------------------
    // tag / 维度聚合：firstTryPassed
    // ------------------------------------------------------------------

    @Test
    void tagAggregationCountsFirstTryPassedAndIgnoresLaterWrongAnswers() {
        Map<Long, TagStat> tags = indexById(profileQuery.practiceByTag(userId, null, null));

        TagStat p1p2 = tags.get(tagP1P2);
        assertNotNull(p1p2, "p1/p2 的标签统计缺失");
        assertEquals(2, p1p2.attempted());          // p1、p2 两题
        assertEquals(2, p1p2.passed());             // 两题都 AC
        assertEquals(2, p1p2.firstTryPassed());     // 两题最早一条提交都是 AC；后续 WA 不改变
        assertEquals(4, p1p2.totalSubmissions());
        assertEquals(1250, p1p2.maxDifficulty());   // p2 难度 1250，取最大

        TagStat p8Tag = tags.get(tagP8);
        assertNotNull(p8Tag, "p8 的标签统计缺失");
        assertEquals(1, p8Tag.attempted());
        assertEquals(1, p8Tag.passed());
        assertEquals(0, p8Tag.firstTryPassed());    // p8 最早一条是 WA
        assertEquals(2, p8Tag.totalSubmissions());
    }

    @Test
    void dimensionRollupUsesLeafTagsOnly() {
        Map<String, DimensionSummary> dimensions = profileQuery.practiceByDimension(userId, null, null);

        DimensionSummary algorithm = dimensions.get("ALGORITHM");
        assertNotNull(algorithm, "ALGORITHM 维度聚合缺失");
        // 两个叶子标签都挂 ALGORITHM：p1、p2、p8 三题；firstTry 只有 p1/p2
        assertEquals(3, algorithm.attempted());
        assertEquals(3, algorithm.passed());
        assertEquals(2, algorithm.firstTryPassed());
        assertEquals(6, algorithm.totalSubmissions());
    }

    // ------------------------------------------------------------------
    // 难度分桶
    // ------------------------------------------------------------------

    @Test
    void difficultyBucketsGroupByHundredsAndExcludeFilteredProblems() {
        Map<Integer, DifficultyCount> buckets = new HashMap<>();
        for (DifficultyCount count : profileQuery.practiceDifficulty(userId, null, null)) {
            buckets.put(count.rating(), count);
        }

        // all 范围有数据的 8 个桶（1250 归入 1200；p3/p4/pDeleted 被排除所以没有 1700/1800/1900）
        assertEquals(8, buckets.size());
        assertBucket(buckets, 800, 1, 1);    // p1
        assertBucket(buckets, 1000, 1, 1);   // p5 比赛
        assertBucket(buckets, 1100, 1, 1);   // p6 VP
        assertBucket(buckets, 1200, 1, 1);   // p2（1250 → 1200）
        assertBucket(buckets, 1300, 1, 1);   // p7（窗口外，all 计入）
        assertBucket(buckets, 1400, 1, 1);   // p8
        assertBucket(buckets, 1500, 0, 1);   // p9 只尝试未通过
        assertBucket(buckets, 1600, 0, 1);   // p11 只尝试未通过
        assertFalse(buckets.containsKey(1700), "is_deleted=1 的题目不应进入难度统计");
        assertFalse(buckets.containsKey(1800), "待判提交不应进入难度统计");
        assertFalse(buckets.containsKey(1900), "judge_phase=FAILED 提交不应进入难度统计");

        // 30d 窗口：p7（1300）被排除
        Map<Integer, DifficultyCount> recent = new HashMap<>();
        for (DifficultyCount count : profileQuery.practiceDifficulty(
                userId, today.minusDays(29).atStartOfDay(), today.plusDays(1).atStartOfDay())) {
            recent.put(count.rating(), count);
        }
        assertEquals(7, recent.size());
        assertFalse(recent.containsKey(1300), "30 天窗口应排除 40 天前的提交");
    }

    // ------------------------------------------------------------------
    // 已通过 / 尝试列表
    // ------------------------------------------------------------------

    @Test
    void solvedProblemsOrderedBySolvedAtAndLimited() {
        List<Long> solved = ids(profileQuery.practiceSolvedProblems(userId, null, null, 50));

        // solvedAt = 首次 AC 时间，按倒序：p8(+35m) > p6(+25m) > p5(+20m) > p2(+10m) > p1(+0m) > p7(40 天前)
        assertEquals(List.of(p8, p6, p5, p2, p1, p7), solved);
        assertFalse(solved.contains(pDeleted), "is_deleted=1 的题目不应出现在已通过列表");

        // LIMIT 生效：只取最近 2 条
        assertEquals(List.of(p8, p6),
                ids(profileQuery.practiceSolvedProblems(userId, null, null, 2)));
    }

    @Test
    void triedProblemsContainOnlyProblemsWithoutAcceptedSubmission() {
        // all 范围：p9、p11 只有 WA；p11 最后提交更晚排在前面
        assertEquals(List.of(p11, p9),
                ids(profileQuery.practiceTriedProblems(userId, null, null, 50)));

        // 30d 窗口：p7 完全在窗口外（不出现），其余同
        assertEquals(List.of(p11, p9), ids(profileQuery.practiceTriedProblems(
                userId, today.minusDays(29).atStartOfDay(), today.plusDays(1).atStartOfDay(), 50)));
    }

    // ------------------------------------------------------------------
    // fixture 辅助
    // ------------------------------------------------------------------

    private Long insertUser() {
        String username = "practice_it_" + UUID.randomUUID();
        jdbc.update("INSERT INTO user(username, password, nickname, rating) VALUES (?, 'test-only', ?, 1200)",
                username, username);
        return jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }

    private Long insertProblem(int difficulty, int isDeleted) {
        String title = "practice-it-" + UUID.randomUUID();
        jdbc.update("INSERT INTO problem(title, difficulty, is_deleted) VALUES (?, ?, ?)",
                title, difficulty, isDeleted);
        Long id = jdbc.queryForObject("SELECT id FROM problem WHERE title = ?", Long.class, title);
        problemIds.add(id);
        return id;
    }

    /** 返回 ALGORITHM 维度根标签 id（Flyway 种子数据，V5 建五维、V9 加 DP 维度后仍然存在）。 */
    private Long insertTags() {
        Long dimensionId = jdbc.query(
                "SELECT id FROM tag WHERE parent_id IS NULL AND dimension = 'ALGORITHM' ORDER BY sort_order LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null);
        assertNotNull(dimensionId, "ALGORITHM 维度标签不存在：Flyway 种子数据未执行？");
        return dimensionId;
    }

    private Long insertTag(String code, Long parentId) {
        jdbc.update("INSERT INTO tag(code, name, parent_id, dimension, sort_order) VALUES (?, ?, ?, 'ALGORITHM', 999)",
                code, code, parentId);
        Long id = jdbc.queryForObject("SELECT id FROM tag WHERE code = ?", Long.class, code);
        tagIds.add(id);
        return id;
    }

    private void linkProblemTag(Long problemId, Long tagId) {
        jdbc.update("INSERT INTO problem_tag(problem_id, tag_id) VALUES (?, ?)", problemId, tagId);
    }

    private void submitFor(Long user, Long problemId, int status, String judgePhase, LocalDateTime createTime) {
        jdbc.update("INSERT INTO submission(user_id, problem_id, language, status, score, run_time, create_time, judge_phase) "
                        + "VALUES (?, ?, 'cpp', ?, ?, ?, ?, ?)",
                user, problemId, status, status == 1 ? 100 : 0, status == 1 ? 10 : 0, createTime, judgePhase);
    }

    private void submit(Long problemId, int status, String judgePhase, LocalDateTime createTime,
                        Long contestId, Long vpAttemptId) {
        if (contestId != null) {
            jdbc.update("INSERT INTO submission(user_id, problem_id, language, status, score, run_time, create_time, "
                            + "contest_id, judge_phase) VALUES (?, ?, 'cpp', ?, ?, ?, ?, ?, ?)",
                    userId, problemId, status, status == 1 ? 100 : 0, status == 1 ? 10 : 0,
                    createTime, contestId, judgePhase);
        } else if (vpAttemptId != null) {
            jdbc.update("INSERT INTO submission(user_id, problem_id, language, status, score, run_time, create_time, "
                            + "vp_attempt_id, judge_phase) VALUES (?, ?, 'cpp', ?, ?, ?, ?, ?, ?)",
                    userId, problemId, status, status == 1 ? 100 : 0, status == 1 ? 10 : 0,
                    createTime, vpAttemptId, judgePhase);
        } else {
            submitFor(userId, problemId, status, judgePhase, createTime);
        }
    }

    private static List<Long> ids(List<ProblemAttemptRow> rows) {
        return rows.stream().map(ProblemAttemptRow::problemId).toList();
    }

    private static Map<Long, TagStat> indexById(List<TagStat> stats) {
        Map<Long, TagStat> byId = new HashMap<>();
        for (TagStat stat : stats) {
            byId.put(stat.tagId(), stat);
        }
        return byId;
    }

    private static void assertBucket(Map<Integer, DifficultyCount> buckets, int rating,
                                     int solved, int attempted) {
        DifficultyCount count = buckets.get(rating);
        assertNotNull(count, "缺少难度桶 " + rating);
        assertEquals(solved, count.solved(), "难度桶 " + rating + " solved");
        assertEquals(attempted, count.attempted(), "难度桶 " + rating + " attempted");
    }
}
