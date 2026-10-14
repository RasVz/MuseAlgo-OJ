package top.everblue.oj.problem.application;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemManagementApplicationServiceTest {

    @Test
    void importedJudgeCaseScoresAlwaysTotalOneHundred() {
        for (int caseCount : new int[]{1, 3, 10, 101, 500}) {
            int total = IntStream.range(0, caseCount)
                    .map(index -> ProblemCaseApplicationService.importedCaseScore(index, caseCount))
                    .sum();

            assertThat(total).isEqualTo(100);
        }
    }

    @Test
    void importedStatementsRemoveExecutableHtml() {
        String sanitized = ProblemManagementApplicationService.sanitizeImportedStatement(
                "<p onclick=\"alert(1)\">safe</p><script>alert(2)</script>");

        assertThat(sanitized).contains("<p>safe</p>").doesNotContain("onclick", "script", "alert");
    }

    @Test
    void importedStatementsKeepStructuredAdditionalSamples() {
        String sanitized = ProblemManagementApplicationService.sanitizeImportedStatement(
                "<section><h3>样例 2</h3><p>输入</p><pre><code>&lt;tag&gt;\n2</code></pre>"
                        + "<p>输出</p><pre><code>two</code></pre></section>");

        assertThat(sanitized)
                .contains("<h3>样例 2</h3>", "<pre><code>&lt;tag&gt;\n2</code></pre>",
                        "<pre><code>two</code></pre>");
    }
}
