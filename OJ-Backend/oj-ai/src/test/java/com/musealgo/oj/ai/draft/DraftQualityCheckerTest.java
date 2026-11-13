package com.musealgo.oj.ai.draft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 质量闸门是「能不能直接落库」的唯一依据，因此每条 ERROR 规则都要有正反用例。 */
class DraftQualityCheckerTest {

    private final DraftBrief brief = DraftBrief.defaults();

    private DraftContent content(String title, String body, List<DraftSample> samples, List<String> tags) {
        return new DraftContent(title, body, "第一行 n", "输出答案", "", 1200, tags, samples, 1000, 512);
    }

    private DraftContent healthy() {
        String body = "小慕在睿思学院门口排队，队伍里每个人都有一个身高。"
                + "给出 n 个整数表示身高，请输出最高的人的身高。"
                + "数据范围：1 ≤ n ≤ 10^5，1 ≤ 身高 ≤ 200。";
        return content("排队看身高", body,
                List.of(new DraftSample("3\n1 5 3", "5", "最高的是 5"),
                        new DraftSample("1\n7", "7", "只有一个人")),
                List.of("模拟"));
    }

    @Test
    void healthyDraftHasNoBlockingIssue() {
        assertFalse(DraftQualityChecker.hasBlockingIssue(DraftQualityChecker.check(healthy(), brief)));
    }

    @Test
    void emptyTitleBlocks() {
        List<DraftIssue> issues = DraftQualityChecker.check(
                content(" ", healthy().content(), healthy().samples(), healthy().tags()), brief);
        assertTrue(hasCode(issues, "TITLE_EMPTY"));
    }

    @Test
    void overlyLongTitleBlocks() {
        List<DraftIssue> issues = DraftQualityChecker.check(
                content("题".repeat(129), healthy().content(), healthy().samples(), healthy().tags()), brief);
        assertTrue(hasCode(issues, "TITLE_TOO_LONG"));
    }

    @Test
    void emptyBodyBlocks() {
        List<DraftIssue> issues = DraftQualityChecker.check(
                content("T", "", healthy().samples(), healthy().tags()), brief);
        assertTrue(hasCode(issues, "CONTENT_EMPTY"));
    }

    @Test
    void unbalancedFenceBlocks() {
        List<DraftIssue> issues = DraftQualityChecker.check(
                content("T", healthy().content() + "\n```\ncode", healthy().samples(), healthy().tags()), brief);
        assertTrue(hasCode(issues, "CODE_FENCE_UNBALANCED"));
    }

    @Test
    void unbalancedFenceDoesNotAlsoReportLatex() {
        List<DraftIssue> issues = DraftQualityChecker.check(
                content("T", healthy().content() + "\n```\n$x", healthy().samples(), healthy().tags()), brief);
        assertFalse(hasCode(issues, "LATEX_UNBALANCED"));
    }

    @Test
    void noSamplesBlocks() {
        List<DraftIssue> issues = DraftQualityChecker.check(
                content("T", healthy().content(), List.of(), healthy().tags()), brief);
        assertTrue(hasCode(issues, "SAMPLE_EMPTY"));
    }

    @Test
    void duplicatedSampleWarns() {
        DraftSample sample = new DraftSample("1", "2", "");
        List<DraftIssue> issues = DraftQualityChecker.check(
                content("T", healthy().content(), List.of(sample, sample), healthy().tags()), brief);
        assertTrue(hasCode(issues, "SAMPLE_DUPLICATED"));
    }

    @Test
    void missingRangeWarns() {
        List<DraftIssue> issues = DraftQualityChecker.check(
                content("T", "小慕在排队，请输出最高身高。".repeat(6), healthy().samples(), healthy().tags()), brief);
        assertTrue(hasCode(issues, "CONTENT_MISSING_RANGE"));
    }

    @Test
    void shortBodyWarns() {
        List<DraftIssue> issues = DraftQualityChecker.check(
                content("T", "太短了", healthy().samples(), healthy().tags()), brief);
        assertTrue(hasCode(issues, "CONTENT_TOO_SHORT"));
    }

    @Test
    void emptyTagsWarn() {
        List<DraftIssue> issues = DraftQualityChecker.check(
                content("T", healthy().content(), healthy().samples(), List.of()), brief);
        assertTrue(hasCode(issues, "TAGS_EMPTY"));
    }

    @Test
    void difficultyDriftWarns() {
        DraftContent drifted = new DraftContent("T", healthy().content(), "in", "out", "", 2600,
                healthy().tags(), healthy().samples(), 1000, 512);
        List<DraftIssue> issues = DraftQualityChecker.check(drifted, brief);
        assertTrue(hasCode(issues, "DIFFICULTY_OUT_OF_BAND"));
    }

    @Test
    void sensitiveWordWarns() {
        List<DraftIssue> issues = DraftQualityChecker.check(
                content("T", healthy().content() + "最后杀死怪物。", healthy().samples(), healthy().tags()), brief);
        assertTrue(hasCode(issues, "SENSITIVE_WORD"));
    }

    @Test
    void blockingIssueScopedToSelectedFields() {
        List<DraftIssue> issues = List.of(DraftIssue.error("SAMPLE_EMPTY", "samples", "没有样例"));
        assertTrue(DraftQualityChecker.blockingIssues(issues, Set.of("samples")).size() == 1);
        assertTrue(DraftQualityChecker.blockingIssues(issues, Set.of("title")).isEmpty());
    }

    private static boolean hasCode(List<DraftIssue> issues, String code) {
        return issues.stream().anyMatch(issue -> issue.code().equals(code));
    }
}
