package top.everblue.oj.analytics.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import top.everblue.oj.analytics.domain.StudentProfile;
import top.everblue.oj.analytics.domain.MasteryLevel;
import top.everblue.oj.analytics.domain.StudentProfile.DimensionMastery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 周报图表数据构建器。
 * <p>
 * 后端只产出 JSON 数据，渲染完全交给模板内嵌的 SVG；避免依赖外部图形库。
 */
@Component
public class ChartOptionBuilder {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> DIMENSION_ORDER = List.of(
            "ALGORITHM", "DATA_STRUCTURE", "DP", "MATH", "GRAPH", "STRING");

    /**
     * 能力雷达图：六知识域维度（算法基础/数据结构/动态规划/数学/图论/字符串）。
     * 维度顺序固定，与模板图例一致。
     */
    public String buildRadarOption(StudentProfile profile) {
        List<DimensionMastery> dims = orderedDimensions(profile);

        ObjectNode root = JSON.createObjectNode();
        ObjectNode radar = root.putObject("radar");

        ArrayNode indicator = radar.putArray("indicator");
        for (DimensionMastery d : dims) {
            ObjectNode item = indicator.addObject();
            item.put("name", d.label());
            item.put("max", 100);
        }
        radar.put("radius", "62%");
        radar.put("shape", "polygon");
        ObjectNode splitArea = radar.putObject("splitArea");
        splitArea.put("show", true);

        ArrayNode series = root.putArray("series");
        ObjectNode s = series.addObject();
        s.put("type", "radar");
        ArrayNode data = s.putArray("data");
        ObjectNode dataItem = data.addObject();
        ArrayNode value = dataItem.putArray("value");
        for (DimensionMastery d : dims) {
            value.add(d.mastery());
        }
        dataItem.put("name", "掌握度");
        ObjectNode areaStyle = dataItem.putObject("areaStyle");
        areaStyle.put("opacity", 0.25);
        ObjectNode lineStyle = dataItem.putObject("lineStyle");
        lineStyle.put("width", 2);
        lineStyle.put("color", "#3b82f6");
        ObjectNode itemStyle = dataItem.putObject("itemStyle");
        itemStyle.put("color", "#3b82f6");

        return root.toString();
    }

    /**
     * 能力变化横向堆叠柱状图：灰色底=上周值，蓝色顶=本周增长。
     * <p>
     * 一根柱子分两段堆叠：
     * <ul>
     *   <li>灰色「上周」段 = min(上周值, 本周值)（本周退步时截断，避免灰超过蓝）</li>
     *   <li>蓝色「本周增长」段 = max(0, 本周值 - 上周值)</li>
     * </ul>
     * 柱子总长 = 本周值，与雷达图数值一致。缺失上周数据时上周视为 0，
     * 整根柱子显示为蓝色增长，保证图表恒定渲染。
     *
     * @param profile           本周画像（非空）
     * @param lastWeekMasteries 上周各维度掌握度映射 (dimension → mastery)，可为 null
     * @return ECharts option JSON 字符串（始终非空）
     */
    public String buildComparisonBarChart(StudentProfile profile,
                                          Map<String, Integer> lastWeekMasteries) {
        List<DimensionMastery> dims = orderedDimensions(profile);

        // 各维度上周值（缺失则 0）
        List<Integer> lastWeekData = dims.stream()
                .map(d -> lastWeekMasteries == null ? 0
                        : lastWeekMasteries.getOrDefault(d.dimension(), 0))
                .toList();

        // 堆叠两段：base = 上周截断到本周；growth = 本周相对上周的增长（退步时为 0）
        List<Integer> baseValues = new ArrayList<>(dims.size());
        List<Integer> growthValues = new ArrayList<>(dims.size());
        for (int i = 0; i < dims.size(); i++) {
            int thisWeek = dims.get(i).mastery();
            int lastWeek = lastWeekData.get(i);
            baseValues.add(Math.min(lastWeek, thisWeek));
            growthValues.add(Math.max(0, thisWeek - lastWeek));
        }

        ObjectNode root = JSON.createObjectNode();

        // tooltip：汇总为「上周 N → 本周 M」
        ObjectNode tooltip = root.putObject("tooltip");
        tooltip.put("trigger", "axis");
        ObjectNode axisPointer = tooltip.putObject("axisPointer");
        axisPointer.put("type", "shadow");
        tooltip.put("valueFormatter", "{value}");

        // legend
        ObjectNode legend = root.putObject("legend");
        ArrayNode legendData = legend.putArray("data");
        legendData.add("上周");
        legendData.add("本周增长");
        legend.put("top", "0");

        // grid
        ObjectNode grid = root.putObject("grid");
        grid.put("left", "3%");
        grid.put("right", "12%");
        grid.put("bottom", "3%");
        grid.put("containLabel", true);

        // xAxis
        ObjectNode xAxis = root.putObject("xAxis");
        xAxis.put("type", "value");
        xAxis.put("max", 100);
        xAxis.put("min", 0);

        // yAxis: 维度名称逆序（ECharts y 轴从下到上，逆序后第一维度在顶部）
        ObjectNode yAxis = root.putObject("yAxis");
        yAxis.put("type", "category");
        ArrayNode yData = yAxis.putArray("data");
        for (int i = dims.size() - 1; i >= 0; i--) {
            yData.add(dims.get(i).label());
        }

        // series：两段堆叠（先画灰色底，再画蓝色增长）
        ArrayNode series = root.putArray("series");

        // 上周（灰色底）
        ObjectNode sBase = series.addObject();
        sBase.put("name", "上周");
        sBase.put("type", "bar");
        sBase.put("stack", "growth");
        sBase.put("barWidth", "55%");
        ArrayNode baseArr = sBase.putArray("data");
        for (int i = baseValues.size() - 1; i >= 0; i--) {
            baseArr.add(baseValues.get(i));
        }
        ObjectNode baseItemStyle = sBase.putObject("itemStyle");
        baseItemStyle.put("color", "#cbd5e1");

        // 本周增长（蓝色顶）
        ObjectNode sGrowth = series.addObject();
        sGrowth.put("name", "本周增长");
        sGrowth.put("type", "bar");
        sGrowth.put("stack", "growth");
        ArrayNode growthArr = sGrowth.putArray("data");
        for (int i = growthValues.size() - 1; i >= 0; i--) {
            growthArr.add(growthValues.get(i));
        }
        ObjectNode growthItemStyle = sGrowth.putObject("itemStyle");
        growthItemStyle.put("color", "#3b82f6");
        growthItemStyle.put("borderRadius", 4);
        // label 只在增长段上显示，格式 +N（无增长则不显示）
        ObjectNode growthLabel = sGrowth.putObject("label");
        growthLabel.put("show", true);
        growthLabel.put("position", "right");
        growthLabel.put("color", "#1e40af");
        growthLabel.put("fontWeight", "bold");
        growthLabel.put("formatter", "+{c}");

        return root.toString();
    }

    /**
     * 按固定维度顺序输出，始终包含全部5个维度。
     * 无数据时 mastery 为 0，确保雷达图始终有可渲染的坐标轴。
     */
    private List<DimensionMastery> orderedDimensions(StudentProfile profile) {
        return DIMENSION_ORDER.stream()
                .map(key -> profile.dimensionMasteries().getOrDefault(key,
                        new DimensionMastery(key, labelOf(key), 0, 0, 0, MasteryLevel.WEAK)))
                .toList();
    }

    private static String labelOf(String dimension) {
        return switch (dimension) {
            case "ALGORITHM" -> "算法基础";
            case "DATA_STRUCTURE" -> "数据结构";
            case "DP" -> "动态规划";
            case "MATH" -> "数学";
            case "GRAPH" -> "图论";
            case "STRING" -> "字符串";
            default -> dimension;
        };
    }
}
