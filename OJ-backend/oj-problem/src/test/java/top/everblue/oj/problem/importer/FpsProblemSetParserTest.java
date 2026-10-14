package top.everblue.oj.problem.importer;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FpsProblemSetParserTest {

    private final FpsProblemSetParser parser = new FpsProblemSetParser();

    @Test
    void parsesProblemStatementLimitsSamplesAndJudgeCases() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <fps version="1.2">
                  <item>
                    <title><![CDATA[A + B Problem]]></title>
                    <time_limit unit="s">2</time_limit>
                    <memory_limit unit="kb">65536</memory_limit>
                    <description><![CDATA[<p>Calculate <strong>A + B</strong>.</p>]]></description>
                    <input><![CDATA[Two integers.]]></input>
                    <output><![CDATA[Their sum.]]></output>
                    <sample_input><![CDATA[1 2]]></sample_input>
                    <sample_output><![CDATA[3]]></sample_output>
                    <test_input><![CDATA[1 2\n]]></test_input>
                    <test_output><![CDATA[3\n]]></test_output>
                    <test_input><![CDATA[-5 8\n]]></test_input>
                    <test_output><![CDATA[3\n]]></test_output>
                    <hint><![CDATA[Use 64-bit integers.]]></hint>
                    <source><![CDATA[Local Contest]]></source>
                </item>
                </fps>
                """;

        List<FpsParsedItem> items = parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertThat(items).hasSize(1);
        FpsProblem problem = items.get(0).problem();
        assertThat(problem.title()).isEqualTo("A + B Problem");
        assertThat(problem.description()).contains("Calculate", "<strong>A + B</strong>");
        assertThat(problem.inputDescription()).isEqualTo("Two integers.");
        assertThat(problem.outputDescription()).isEqualTo("Their sum.");
        assertThat(problem.samples()).containsExactly(new FpsSample("1 2", "3"));
        assertThat(problem.hint()).isEqualTo("Use 64-bit integers.");
        assertThat(problem.source()).isEqualTo("Local Contest");
        assertThat(problem.timeLimitMillis()).isEqualTo(2000);
        assertThat(problem.memoryLimitMegabytes()).isEqualTo(64);
        assertThat(problem.testCases()).containsExactly(
                new FpsTestCase("1 2\n", "3\n"),
                new FpsTestCase("-5 8\n", "3\n")
        );
    }

    @Test
    void importsPairedJudgeCasesAndWarnsAboutUnpairedData() {
        String xml = """
                <fps><item><title>Broken</title>
                  <test_input>1</test_input><test_input>2</test_input>
                  <test_output>one</test_output>
                </item></fps>
                """;

        FpsProblem problem = parse(xml).get(0).problem();

        assertThat(problem.testCases()).containsExactly(new FpsTestCase("1", "one"));
        assertThat(problem.warnings()).anyMatch(message -> message.contains("数量不一致"));
    }

    @Test
    void acceptsOfficialDoctypeWithoutLoadingTheExternalDtd() {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE fps PUBLIC "-//freeproblemset//DTD" "http://hustoj.com/fps.current.dtd">
                <fps version="1.4"><item><title>Official FPS</title>
                  <time_limit unit="s">1.000</time_limit>
                  <test_input>1</test_input><test_output>1</test_output>
                </item></fps>
                """;

        FpsProblem problem = parse(xml).get(0).problem();

        assertThat(problem.title()).isEqualTo("Official FPS");
        assertThat(problem.timeLimitMillis()).isEqualTo(1000);
    }

    @Test
    void neverResolvesExternalEntitiesDeclaredByAnFpsFile() {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE fps [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <fps><item><title>&secret;</title>
                  <test_input>1</test_input><test_output>1</test_output>
                </item></fps>
                """;

        List<FpsParsedItem> items = parse(xml);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).success()).isTrue();
        assertThat(items.get(0).problem().title()).isEqualTo("未命名题目 #1");
        assertThat(items.get(0).problem().title()).doesNotContain("root:");
        assertThat(items.get(0).problem().warnings()).contains("原文件缺少标题，已生成占位标题");
    }

    @Test
    void treatsAnOmittedTimeUnitAsSecondsForLegacyFpsFiles() {
        String xml = """
                <fps version="1.1"><item><title>Legacy</title>
                  <time_limit>2</time_limit><memory_limit>128</memory_limit>
                  <test_input>1</test_input><test_output>1</test_output>
                </item></fps>
                """;

        FpsProblem problem = parse(xml).get(0).problem();

        assertThat(problem.timeLimitMillis()).isEqualTo(2000);
        assertThat(problem.memoryLimitMegabytes()).isEqualTo(128);
    }

    @Test
    void importsStatementsWithoutJudgeCasesWithAWarning() {
        FpsProblem problem = parse("<fps><item><title>No cases</title></item></fps>").get(0).problem();

        assertThat(problem.testCases()).isEmpty();
        assertThat(problem.warnings()).contains("文件未包含本地测试数据，仅导入题面");
    }

    @Test
    void usesDefaultTimeLimitAndWarnsWhenConversionOverflows() {
        String xml = """
                <fps><item><title>Overflow</title>
                  <time_limit unit="s">9223372036854775807</time_limit>
                  <test_input>1</test_input><test_output>1</test_output>
                </item></fps>
                """;

        FpsProblem problem = parse(xml).get(0).problem();

        assertThat(problem.timeLimitMillis()).isEqualTo(1000);
        assertThat(problem.warnings()).anyMatch(message -> message.contains("时间限制超出系统支持范围"));
    }

    @Test
    void usesDefaultLimitsAndWarnsAboutHugeValuesInSmallerUnits() {
        String timeXml = """
                <fps><item><title>Huge time</title>
                  <time_limit unit="us">9223372036854775807</time_limit>
                  <test_input>1</test_input><test_output>1</test_output>
                </item></fps>
                """;
        String memoryXml = """
                <fps><item><title>Huge memory</title>
                  <memory_limit unit="b">9223372036854775807</memory_limit>
                  <test_input>1</test_input><test_output>1</test_output>
                </item></fps>
                """;

        FpsProblem timeProblem = parse(timeXml).get(0).problem();
        FpsProblem memoryProblem = parse(memoryXml).get(0).problem();

        assertThat(timeProblem.timeLimitMillis()).isEqualTo(1000);
        assertThat(timeProblem.warnings()).anyMatch(message -> message.contains("时间限制超出系统支持范围"));
        assertThat(memoryProblem.memoryLimitMegabytes()).isEqualTo(256);
        assertThat(memoryProblem.warnings()).anyMatch(message -> message.contains("内存限制超出系统支持范围"));
    }

    @Test
    void usesDefaultLimitWhenAnOfficialProblemContainsZero() {
        String xml = """
                <fps><item><title>Zero limit</title>
                  <time_limit unit="s">0</time_limit><memory_limit unit="mb">0</memory_limit>
                  <test_input>1</test_input><test_output>1</test_output>
                </item></fps>
                """;

        FpsProblem problem = parse(xml).get(0).problem();

        assertThat(problem.timeLimitMillis()).isEqualTo(1000);
        assertThat(problem.memoryLimitMegabytes()).isEqualTo(256);
        assertThat(problem.warnings()).hasSize(2);
    }

    @Test
    void truncatesTitlesThatDoNotFitTheProblemTable() {
        String xml = "<fps><item><title>" + "x".repeat(129) + "</title>"
                + "<test_input>1</test_input><test_output>1</test_output></item></fps>";

        FpsProblem problem = parse(xml).get(0).problem();

        assertThat(problem.title()).hasSize(128);
        assertThat(problem.warnings()).contains("标题超过 128 个字符，已截断");
    }

    @Test
    void collectsAllSamplesInOrderWithoutPollutingDescription() {
        String xml = """
                <fps><item><title>Samples</title>
                  <sample_input>1</sample_input><sample_output>one</sample_output>
                  <sample_input>2</sample_input><sample_output>two</sample_output>
                  <test_input>1</test_input><test_output>one</test_output>
                </item></fps>
                """;

        FpsProblem problem = parse(xml).get(0).problem();

        assertThat(problem.samples()).containsExactly(
                new FpsSample("1", "one"),
                new FpsSample("2", "two"));
        assertThat(problem.description())
                .doesNotContain("样例 2", "<pre><code>2</code></pre>");
    }

    private List<FpsParsedItem> parse(String xml) {
        return parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
