package com.musealgo.oj.analytics.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import org.springframework.stereotype.Component;
import com.musealgo.oj.analytics.domain.StudentProfile;
import com.musealgo.oj.analytics.domain.TagMastery;

import java.io.IOException;
import java.io.StringWriter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 家长报告 HTML 构建器：画像数据 + FreeMarker 模板 → 自包含 HTML。
 * <p>
 * 模板从 classpath:/templates/weekly-report.ftl 加载；
 * 图表数据以 JSON 形式内嵌，由模板内的 SVG 渲染器绘制。
 * <p>
 * 同一份 buildModel 结果同时供模板与结构化数据节点使用：HTML 是打印/下载的
 * 权威产物，结构化数据是其伴生产物，两者必然同源、不会各自计算。
 */
@Component
public class FreemarkerHtmlBuilder {

    private static final String TEMPLATE_NAME = "weekly-report.ftl";
    private static final String DATA_NODE_ID = "muse-report-data";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Configuration freemarkerConfig;
    private final ChartOptionBuilder chartOptionBuilder;

    public FreemarkerHtmlBuilder(ChartOptionBuilder chartOptionBuilder) {
        this.chartOptionBuilder = chartOptionBuilder;

        this.freemarkerConfig = new Configuration(Configuration.VERSION_2_3_32);
        this.freemarkerConfig.setClassLoaderForTemplateLoading(
                FreemarkerHtmlBuilder.class.getClassLoader(), "/templates");
        this.freemarkerConfig.setDefaultEncoding("UTF-8");
        this.freemarkerConfig.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
    }

    /**
     * @param profile           学生画像（必非空）
     * @param advice            薄弱点建议文案（已含 AI/模板降级逻辑）
     * @param recommendations   推荐题单
     * @param lastWeekMasteries 上周各维度掌握度映射 (dimension → mastery)，可为空
     * @return 完整 HTML 字符串（含结构化数据节点）
     */
    public String buildHtml(StudentProfile profile, String advice,
                            List<RecommendedProblem> recommendations,
                            Map<String, Integer> lastWeekMasteries) {
        return render(profile, advice, recommendations, lastWeekMasteries).html();
    }

    /**
     * 兼容旧调用，不包含上周对比数据。
     */
    public String buildHtml(StudentProfile profile, String advice,
                            List<RecommendedProblem> recommendations) {
        return buildHtml(profile, advice, recommendations, null);
    }

    /**
     * 一次构造数据模型，同时产出 HTML 与结构化数据 JSON。
     */
    public ReportDocument render(StudentProfile profile, String advice,
                                 List<RecommendedProblem> recommendations,
                                 Map<String, Integer> lastWeekMasteries) {
        try {
            Map<String, Object> model = buildModel(profile, advice, recommendations, lastWeekMasteries);
            Template template = freemarkerConfig.getTemplate(TEMPLATE_NAME);
            StringWriter out = new StringWriter();
            template.process(model, out);
            String structuredData = buildStructuredData(model);
            return new ReportDocument(injectDataNode(out.toString(), structuredData), structuredData);
        } catch (IOException | TemplateException e) {
            throw new ReportRenderException("报告模板渲染失败", e);
        }
    }

    public record ReportDocument(String html, String structuredData) {
    }

    /**
     * 结构化数据以不执行的 JSON script 节点附在 HTML 末尾。
     * 把 {@code <} 写成 JSON 的 {@code \u003c} 转义，避免题目/建议文本中的
     * {@code </script>} 提前闭合节点；浏览器解析该节点时不会执行任何脚本。
     */
    private String injectDataNode(String html, String json) {
        String safe = json.replace("<", "\\u003c");
        String node = "<script type=\"application/json\" id=\"" + DATA_NODE_ID + "\">" + safe + "</script>";
        int bodyEnd = html.lastIndexOf("</body>");
        return bodyEnd < 0 ? html + node : html.substring(0, bodyEnd) + node + html.substring(bodyEnd);
    }

    private String buildStructuredData(Map<String, Object> model) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("studentName", model.get("studentName"));
        header.put("className", model.get("className"));
        header.put("periodStart", model.get("periodStart"));
        header.put("periodEnd", model.get("periodEnd"));
        header.put("weekNo", model.get("weekNo"));
        root.put("header", header);

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalSubmissions", model.get("totalSubmissions"));
        overview.put("acceptedProblemCount", model.get("acceptedProblemCount"));
        overview.put("activeDays", model.get("activeDays"));
        root.put("overview", overview);

        root.put("radar", extractRadar(model.get("radarOption")));
        root.put("comparison", extractComparison(model.get("comparisonOption")));
        root.put("tagRows", model.get("tagRows"));
        root.put("weakPoints", model.get("weakPoints"));
        root.put("advice", model.get("advice"));
        root.put("recommendations", model.get("recommendations"));

        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new ReportRenderException("报告结构化数据生成失败", e);
        }
    }

    /** 雷达图 option → 维度名与掌握度数组；无法解析时返回 null，由前端降级原始版。 */
    private Map<String, Object> extractRadar(Object option) {
        if (!(option instanceof String raw) || raw.isBlank()) return null;
        try {
            JsonNode root = JSON.readTree(raw);
            List<String> dimensions = new ArrayList<>();
            List<Integer> values = new ArrayList<>();
            for (JsonNode node : root.path("radar").path("indicator")) {
                dimensions.add(node.path("name").asText());
            }
            for (JsonNode node : root.path("series").path(0).path("data").path(0).path("value")) {
                values.add(node.asInt());
            }
            if (dimensions.isEmpty() || dimensions.size() != values.size()) return null;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dimensions", dimensions);
            result.put("values", values);
            return result;
        } catch (IOException e) {
            return null;
        }
    }

    /** 能力变化 option → 维度名、上周基数、本周增长（保持模板的逆序，前端按序渲染）。 */
    private Map<String, Object> extractComparison(Object option) {
        if (!(option instanceof String raw) || raw.isBlank()) return null;
        try {
            JsonNode root = JSON.readTree(raw);
            List<String> dimensions = new ArrayList<>();
            for (JsonNode node : root.path("yAxis").path("data")) {
                dimensions.add(node.asText());
            }
            List<Integer> base = new ArrayList<>();
            List<Integer> growth = new ArrayList<>();
            for (JsonNode series : root.path("series")) {
                String name = series.path("name").asText();
                List<Integer> target = "上周".equals(name) ? base : "本周增长".equals(name) ? growth : null;
                if (target == null) continue;
                for (JsonNode node : series.path("data")) target.add(node.asInt());
            }
            if (dimensions.isEmpty() || dimensions.size() != base.size() || dimensions.size() != growth.size()) {
                return null;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dimensions", dimensions);
            result.put("base", base);
            result.put("growth", growth);
            return result;
        } catch (IOException e) {
            return null;
        }
    }

    private Map<String, Object> buildModel(StudentProfile profile, String advice,
                                           List<RecommendedProblem> recommendations,
                                           Map<String, Integer> lastWeekMasteries) {
        Map<String, Object> model = new HashMap<>();

        // 头部
        model.put("studentName", profile.studentName() != null ? profile.studentName() : "同学");
        model.put("className", profile.className() != null ? profile.className() : "");
        model.put("periodStart", profile.periodStart().toString());
        model.put("periodEnd", profile.periodEnd().toString());
        model.put("weekNo", profile.periodStart().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));

        // ① 概览卡片
        model.put("totalSubmissions", profile.weekOverview().totalSubmissions());
        model.put("acceptedProblemCount", profile.weekOverview().acceptedProblemCount());
        model.put("activeDays", profile.weekOverview().activeDays());

        // ② 雷达图 option（JSON 字符串，模板里 JSON.parse）
        model.put("radarOption", chartOptionBuilder.buildRadarOption(profile));

        // ②½ 横向对比条状图：本周 vs 上周
        String comparisonOption = chartOptionBuilder.buildComparisonBarChart(profile, lastWeekMasteries);
        model.put("comparisonOption", comparisonOption != null ? comparisonOption : "");

        // ③ 知识点明细表
        model.put("tagRows", profile.tagMasteries().stream()
                .limit(12)
                .map(this::toTagRow)
                .toList());

        // ⑤ 薄弱环节 + 建议
        model.put("weakPoints", profile.weakPoints().stream()
                .limit(3)
                .map(t -> Map.of(
                        "tagName", t.tagName(),
                        "mastery", t.mastery()))
                .toList());
        model.put("advice", advice);

        // ⑥ 推荐题单（record → Map，与 tagRows 一致；Freemarker 对 record 的
        //    `.field` 语法会解析成方法模型导致渲染失败，统一转 Map 最稳妥）
        model.put("recommendations", recommendations.stream()
                .map(r -> {
                    Map<String, Object> m = new HashMap<String, Object>();
                    m.put("tagName", r.tagName());
                    m.put("title", r.title());
                    m.put("difficultyLabel", r.difficultyLabel());
                    return m;
                })
                .toList());

        return model;
    }

    private Map<String, Object> toTagRow(TagMastery t) {
        Map<String, Object> row = new HashMap<>();
        row.put("tagName", t.tagName());
        row.put("attempted", t.attempted());
        row.put("passed", t.passed());
        row.put("firstTryRate", t.attempted() == 0 ? 0
                : Math.round(t.firstTryPassed() * 100.0 / t.attempted()));
        row.put("mastery", t.mastery());
        row.put("levelLabel", t.masteryLevel().getLabel());
        return row;
    }
}
