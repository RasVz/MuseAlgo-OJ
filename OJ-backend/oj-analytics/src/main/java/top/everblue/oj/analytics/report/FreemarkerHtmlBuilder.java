package top.everblue.oj.analytics.report;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import org.springframework.stereotype.Component;
import top.everblue.oj.analytics.domain.StudentProfile;
import top.everblue.oj.analytics.domain.TagMastery;

import java.io.IOException;
import java.io.StringWriter;
import java.time.temporal.IsoFields;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 家长报告 HTML 构建器：画像数据 + FreeMarker 模板 → 自包含 HTML。
 * <p>
 * 模板从 classpath:/templates/weekly-report.ftl 加载；
 * 图表数据以 JSON 形式内嵌，由模板内的 SVG 渲染器绘制。
 */
@Component
public class FreemarkerHtmlBuilder {

    private static final String TEMPLATE_NAME = "weekly-report.ftl";

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
     * @return 完整 HTML 字符串
     */
    public String buildHtml(StudentProfile profile, String advice,
                            List<RecommendedProblem> recommendations,
                            Map<String, Integer> lastWeekMasteries) {
        try {
            Template template = freemarkerConfig.getTemplate(TEMPLATE_NAME);
            StringWriter out = new StringWriter();
            template.process(buildModel(profile, advice, recommendations, lastWeekMasteries), out);
            return out.toString();
        } catch (IOException | TemplateException e) {
            throw new ReportRenderException("报告模板渲染失败", e);
        }
    }

    /**
     * 兼容旧调用，不包含上周对比数据。
     */
    public String buildHtml(StudentProfile profile, String advice,
                            List<RecommendedProblem> recommendations) {
        return buildHtml(profile, advice, recommendations, null);
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
