package com.musealgo.oj.problem.format.quality;

import com.musealgo.oj.problem.format.FormatSample;
import com.musealgo.oj.problem.format.IssueSeverity;
import com.musealgo.oj.problem.format.ProblemFormattedContent;
import com.musealgo.oj.problem.format.ProblemStatementSnapshot;
import com.musealgo.oj.problem.format.StatementIssue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 质量规则的判定边界：每条 ERROR 都对应一种「不该直接落库」的确定情形。 */
class StatementQualityCheckerTest {

    private static final String LONG_CONTENT = "给定一个长度为 $n$ 的整数序列 $a_i$，求所有元素之和。".repeat(6);

    @Test
    void cleanResultHasNoBlockingIssue() {
        List<StatementIssue> issues = StatementQualityChecker.check(source(List.of(sample("1", "1"))),
                formatted(LONG_CONTENT, List.of(sample("1", "1"))));

        assertFalse(StatementQualityChecker.hasBlockingIssue(issues));
    }

    @Test
    void blankTitleIsError() {
        List<StatementIssue> issues = StatementQualityChecker.check(source(List.of()),
                formatted(" ", LONG_CONTENT, List.of()));

        assertIssue(issues, "TITLE_EMPTY", IssueSeverity.ERROR, "title");
    }

    @Test
    void overlongTitleIsError() {
        List<StatementIssue> issues = StatementQualityChecker.check(source(List.of()),
                formatted("题".repeat(129), LONG_CONTENT, List.of()));

        assertIssue(issues, "TITLE_TOO_LONG", IssueSeverity.ERROR, "title");
    }

    @Test
    void blankContentIsError() {
        List<StatementIssue> issues = StatementQualityChecker.check(source(List.of()),
                formatted("  ", List.of()));

        assertIssue(issues, "CONTENT_EMPTY", IssueSeverity.ERROR, "content");
    }

    @Test
    void limitDeclarationLeftInContentIsError() {
        List<StatementIssue> issues = StatementQualityChecker.check(source(List.of()),
                formatted(LONG_CONTENT + "\n\nTime Limit: 2 seconds", List.of()));

        assertIssue(issues, "LIMIT_DECLARATION_LEFT", IssueSeverity.ERROR, "content");
    }

    @Test
    void lostSamplesIsError() {
        List<StatementIssue> issues = StatementQualityChecker.check(
                source(List.of(sample("1", "1"), sample("2", "2"))), formatted(LONG_CONTENT, List.of()));

        assertIssue(issues, "SAMPLES_LOST", IssueSeverity.ERROR, "samples");
        assertTrue(StatementQualityChecker.hasSampleRisk(issues));
    }

    @Test
    void reducedSamplesIsWarningButKeepsAutoApplyAway() {
        List<StatementIssue> issues = StatementQualityChecker.check(
                source(List.of(sample("1", "1"), sample("2", "2"))), formatted(LONG_CONTENT, List.of(sample("1", "1"))));

        assertIssue(issues, "SAMPLES_REDUCED", IssueSeverity.WARN, "samples");
        assertTrue(StatementQualityChecker.hasSampleRisk(issues));
        assertFalse(StatementQualityChecker.hasBlockingIssue(issues));
    }

    @Test
    void duplicatedSampleIsWarning() {
        List<StatementIssue> issues = StatementQualityChecker.check(source(List.of()),
                formatted(LONG_CONTENT, List.of(sample("1", "1"), sample("1", "1"))));

        assertIssue(issues, "SAMPLE_DUPLICATED", IssueSeverity.WARN, "samples");
    }

    @Test
    void changedSampleContentIsWarningButNotSampleRisk() {
        List<StatementIssue> issues = StatementQualityChecker.check(
                source(List.of(sample("1", "1"))), formatted(LONG_CONTENT, List.of(sample("9", "9"))));

        assertIssue(issues, "SAMPLE_CONTENT_CHANGED", IssueSeverity.WARN, "samples");
        assertFalse(StatementQualityChecker.hasSampleRisk(issues));
    }

    @Test
    void unclosedCodeFenceIsError() {
        List<StatementIssue> issues = StatementQualityChecker.check(source(List.of()),
                formatted(LONG_CONTENT + "\n```\nint main() {}", List.of()));

        assertIssue(issues, "CODE_FENCE_UNBALANCED", IssueSeverity.ERROR, "content");
    }

    @Test
    void unbalancedLatexDollarIsWarningAndIgnoresCode() {
        List<StatementIssue> issues = StatementQualityChecker.check(source(List.of()),
                formatted(LONG_CONTENT + "\n\n代价为 $O(n^2)$ 或 $O(n)$，还有 $x", List.of()));

        assertIssue(issues, "LATEX_UNBALANCED", IssueSeverity.WARN, "content");

        List<StatementIssue> withCode = StatementQualityChecker.check(source(List.of()),
                formatted(LONG_CONTENT + "\n\n```\nprintf(\"$100\");\n```", List.of()));
        assertNoIssue(withCode, "LATEX_UNBALANCED");
    }

    @Test
    void heavilyShrunkContentIsError() {
        String longSource = "原始题面内容".repeat(100);
        ProblemStatementSnapshot source = new ProblemStatementSnapshot("题名", longSource, "", "", "",
                1000, 512, List.of());
        List<StatementIssue> issues = StatementQualityChecker.check(source, formatted("短正文", List.of()));

        assertIssue(issues, "CONTENT_LOST", IssueSeverity.ERROR, "content");
    }

    @Test
    void blockingIssuesAreFilteredByAppliedFields() {
        List<StatementIssue> issues = StatementQualityChecker.check(source(List.of(sample("1", "1"))),
                formatted("题名", " ", List.of()));

        assertTrue(StatementQualityChecker.hasBlockingIssue(issues));
        assertTrue(StatementQualityChecker.hasBlockingIssue(issues, Set.of("content", "samples")));
        assertFalse(StatementQualityChecker.hasBlockingIssue(issues, Set.of("title")));
    }

    private static void assertIssue(List<StatementIssue> issues, String code, IssueSeverity severity, String field) {
        StatementIssue matched = issues.stream().filter(issue -> issue.code().equals(code)).findFirst().orElse(null);
        assertEquals(code, matched == null ? null : matched.code(), "缺少问题码 " + code);
        assertEquals(severity, matched.severity());
        assertEquals(field, matched.field());
    }

    private static void assertNoIssue(List<StatementIssue> issues, String code) {
        assertTrue(issues.stream().noneMatch(issue -> issue.code().equals(code)), "不应出现 " + code);
    }

    private static ProblemStatementSnapshot source(List<FormatSample> samples) {
        return new ProblemStatementSnapshot("题名", LONG_CONTENT, "输入", "输出", "提示", 1000, 512, samples);
    }

    private static ProblemFormattedContent formatted(String content, List<FormatSample> samples) {
        return formatted("题名", content, samples);
    }

    private static ProblemFormattedContent formatted(String title, String content, List<FormatSample> samples) {
        return new ProblemFormattedContent(title, content, "输入格式", "输出格式", "提示", 1000, 512, samples);
    }

    private static FormatSample sample(String input, String output) {
        return new FormatSample(input, output);
    }
}
