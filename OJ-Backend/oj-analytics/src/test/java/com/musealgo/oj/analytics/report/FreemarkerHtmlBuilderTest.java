package com.musealgo.oj.analytics.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musealgo.oj.analytics.domain.MasteryLevel;
import com.musealgo.oj.analytics.domain.StudentProfile;
import com.musealgo.oj.analytics.domain.TagMastery;
import com.musealgo.oj.api.profile.ProfileQuery;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构化数据契约：HTML 与 JSON 由同一份 buildModel 产出，契约节点位于 </body> 前，
 * 且正文中的 &lt;/script&gt; 不会提前闭合节点。
 */
class FreemarkerHtmlBuilderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NODE_OPEN = "<script type=\"application/json\" id=\"muse-report-data\">";

    private FreemarkerHtmlBuilder builder() {
        return new FreemarkerHtmlBuilder(new ChartOptionBuilder());
    }

    private StudentProfile profile() {
        LocalDate start = LocalDate.of(2026, 11, 2);
        LocalDate end = start.plusDays(6);
        TagMastery tag = new TagMastery(1L, "贪心", null, "ALGORITHM",
                5, 4, 3, 6, 1200.0, 1200, 72, MasteryLevel.of(72));
        return new StudentProfile(1L, "测试学生", 2L, "算法基础班", start, end,
                new ProfileQuery.WeekOverview(12, 10, 8, 5),
                List.of(tag), Map.of(), Map.of(), List.of(tag));
    }

    private JsonNode readNode(String html) throws Exception {
        int open = html.indexOf(NODE_OPEN);
        assertTrue(open > 0, "HTML 应包含结构化数据契约节点");
        int start = open + NODE_OPEN.length();
        int end = html.indexOf("</script>", start);
        assertTrue(end > start, "契约节点必须完整闭合");
        return JSON.readTree(html.substring(start, end));
    }

    @Test
    void renderWritesStructuredDataFromTheSameModel() throws Exception {
        var document = builder().render(profile(), "建议加强练习",
                List.of(new RecommendedProblem("图论", "最短路练习", "中等")), Map.of());

        assertTrue(document.html().contains("信奥学习周报"), "HTML 正文仍由模板渲染");
        assertTrue(document.html().lastIndexOf(NODE_OPEN) < document.html().lastIndexOf("</body>"));

        JsonNode node = readNode(document.html());
        assertEquals(1, node.path("schemaVersion").asInt());
        assertEquals("测试学生", node.path("header").path("studentName").asText());
        assertEquals("算法基础班", node.path("header").path("className").asText());
        assertEquals("2026-11-02", node.path("header").path("periodStart").asText());
        assertEquals(12, node.path("overview").path("totalSubmissions").asInt());
        assertEquals(8, node.path("overview").path("acceptedProblemCount").asInt());
        assertEquals(5, node.path("overview").path("activeDays").asInt());
        assertEquals(6, node.path("radar").path("dimensions").size());
        assertEquals(6, node.path("radar").path("values").size());
        assertEquals(6, node.path("comparison").path("base").size());
        assertEquals(6, node.path("comparison").path("growth").size());
        assertEquals(1, node.path("tagRows").size());
        assertEquals("贪心", node.path("tagRows").path(0).path("tagName").asText());
        assertEquals(1, node.path("weakPoints").size());
        assertEquals("建议加强练习", node.path("advice").asText());
        assertEquals("最短路练习", node.path("recommendations").path(0).path("title").asText());

        // render 同时返回结构化数据字符串（供未来独立字段使用），内容与节点一致。
        assertEquals("测试学生", JSON.readTree(document.structuredData()).path("header").path("studentName").asText());
    }

    @Test
    void closingScriptInTextIsEscapedInHtmlButRestoredInJson() throws Exception {
        String advice = "建议 </script> 加强练习";
        var document = builder().render(profile(), advice, List.of(), Map.of());

        assertFalse(document.html().contains(advice), "HTML 中不得出现可提前闭合节点的裸标签");
        assertEquals(advice, readNode(document.html()).path("advice").asText());
    }

    @Test
    void buildHtmlKeepsLegacySignatureAndStillCarriesTheNode() {
        String html = builder().buildHtml(profile(), "建议", List.of());
        assertTrue(html.contains(NODE_OPEN));
        assertTrue(html.contains("</body>"));
    }
}
