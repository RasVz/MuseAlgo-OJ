package com.musealgo.oj.ai.draft;

import java.util.ArrayList;
import java.util.List;

/**
 * 命题简报：比「知识点 + 难度」更完整的生成输入。
 * <p>
 * 之所以把这些维度显式建模而不是塞进一个自由文本框：教师每次命题的差异主要在
 * 年级、场景类型、样例组数与禁忌约束上，结构化之后既能稳定拼进提示词，
 * 也能作为「复制上次简报再改一处」的复用单位。
 * </p>
 * <p>
 * 难度只存分值（{@code difficultyScore}），档位名由分值反查：题库本身按分值存储，
 * 若简报再存一份档位名，两者迟早不一致。档位区间与前端 {@code utils/difficulty.js} 同源。
 * </p>
 *
 * @param theme       {@code ip}=睿思星球世界观，{@code neutral}=中性命名，{@code custom}=自定义设定
 * @param sampleCount 期望样例组数，2~5
 */
public record DraftBrief(
        String knowledgePoint,
        Integer difficultyScore,
        String grade,
        String scenarioType,
        String scale,
        Integer sampleCount,
        String theme,
        String customTheme,
        String forbidden,
        String reference,
        String extra) {

    /** 与前端 {@code utils/difficulty.js} 保持同序同区间；这里是服务端校验与提示词构造的唯一来源。 */
    public record Band(String label, int min, int max) {
    }

    public static final List<Band> BANDS = List.of(
            new Band("入门", 800, 900),
            new Band("入门+", 1000, 1100),
            new Band("普及-", 1200, 1300),
            new Band("普及", 1400, 1500),
            new Band("普及+", 1600, 1700),
            new Band("提高-", 1800, 1900),
            new Band("提高", 2000, 2100),
            new Band("提高+", 2200, 2300),
            new Band("省选-", 2400, 2500),
            new Band("省选 / NOI-", 2600, 2700),
            new Band("NOI 及以上", 2800, 3000));

    public static final List<String> GRADES = List.of("四年级", "五年级", "六年级", "初一");
    public static final List<String> SCENARIO_TYPES = List.of("生活故事", "校园故事", "太空冒险", "游戏关卡", "纯数学包装");
    public static final List<String> THEMES = List.of("ip", "neutral", "custom");

    public static final int MIN_SCORE = 800;
    public static final int MAX_SCORE = 3000;

    private static final int MAX_SHORT = 60;
    private static final int MAX_MEDIUM = 300;
    private static final int MAX_LONG = 1000;

    public static DraftBrief defaults() {
        return new DraftBrief("贪心", 1200, "五年级", "校园故事", "n ≤ 10^5", 3,
                "ip", "", "", "", "");
    }

    public int sampleCountOrDefault() {
        return sampleCount == null ? 3 : sampleCount;
    }

    public String themeOrDefault() {
        return theme == null || theme.isBlank() ? "ip" : theme;
    }

    public boolean ipMode() {
        return "ip".equals(themeOrDefault());
    }

    public int scoreOrDefault() {
        return difficultyScore == null ? 1200 : difficultyScore;
    }

    /** 分值落在哪个档位；落在档位间隙（如 950）时取距离最近的档位，保证展示永远有值。 */
    public Band band() {
        return bandOf(scoreOrDefault());
    }

    /** 供列表展示按分值反查档位，不要求同时持有整份简报。 */
    public static Band bandOf(int score) {
        Band nearest = BANDS.get(0);
        int best = Integer.MAX_VALUE;
        for (Band band : BANDS) {
            if (score >= band.min() && score <= band.max()) {
                return band;
            }
            int distance = score < band.min() ? band.min() - score : score - band.max();
            if (distance < best) {
                best = distance;
                nearest = band;
            }
        }
        return nearest;
    }

    public String bandLabel() {
        return band().label();
    }

    /** 列表与历史卡片的一行摘要，避免前端自己拼字段顺序。 */
    public String summary() {
        StringBuilder text = new StringBuilder();
        text.append(knowledgePoint == null || knowledgePoint.isBlank() ? "未指定知识点" : knowledgePoint.trim());
        text.append(" · ").append(bandLabel());
        if (grade != null && !grade.isBlank()) {
            text.append(" · ").append(grade.trim());
        }
        text.append(" · ").append(sampleCountOrDefault()).append(" 组样例");
        // 按码点截断：按 char 截会切在代理对中间，生成非法字符串
        if (text.codePointCount(0, text.length()) <= 255) {
            return text.toString();
        }
        return text.substring(0, text.offsetByCodePoints(0, 255));
    }

    /**
     * 校验并返回面向教师的中文问题列表；空列表表示可用。
     * <p>不在校验里抛异常，是为了让「提示词预览」与「受理生成」共用同一套判定并把全部问题一次说清。</p>
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (isBlank(knowledgePoint)) {
            errors.add("请填写知识点");
        } else if (codePoints(knowledgePoint) > MAX_SHORT) {
            errors.add("知识点不能超过 " + MAX_SHORT + " 个字符");
        }
        if (difficultyScore == null) {
            errors.add("请选择难度分值");
        } else if (difficultyScore < MIN_SCORE || difficultyScore > MAX_SCORE) {
            errors.add("难度分值需在 " + MIN_SCORE + "~" + MAX_SCORE + " 之间");
        }
        if (!isBlank(grade) && !GRADES.contains(grade.trim())) {
            errors.add("年级不在可选范围内");
        }
        if (!isBlank(scenarioType) && !SCENARIO_TYPES.contains(scenarioType.trim())) {
            errors.add("场景类型不在可选范围内");
        }
        if (!isBlank(scale) && codePoints(scale) > MAX_SHORT) {
            errors.add("数据规模不能超过 " + MAX_SHORT + " 个字符");
        }
        if (sampleCount != null && (sampleCount < 2 || sampleCount > 5)) {
            errors.add("样例组数需在 2~5 之间");
        }
        if (!isBlank(theme) && !THEMES.contains(theme.trim())) {
            errors.add("世界观选项不合法");
        }
        if ("custom".equals(themeOrDefault()) && isBlank(customTheme)) {
            errors.add("选择自定义世界观时请填写世界观设定");
        }
        checkLength(errors, customTheme, MAX_MEDIUM, "世界观设定");
        checkLength(errors, forbidden, MAX_MEDIUM, "禁忌要求");
        checkLength(errors, reference, MAX_MEDIUM, "参考题");
        checkLength(errors, extra, MAX_LONG, "附加要求");
        return List.copyOf(errors);
    }

    /** 把越界分值收敛到合法区间；返回新对象，避免在只读语义的记录上就地改动。 */
    public DraftBrief normalized() {
        // 吸附到 100 的整数倍：题库按 100 分步长录入，1350 这种值在筛选与统计里会成为孤点
        int raw = Math.round(scoreOrDefault() / 100f) * 100;
        int score = Math.max(MIN_SCORE, Math.min(MAX_SCORE, raw));
        int count = Math.max(2, Math.min(5, sampleCountOrDefault()));
        return new DraftBrief(trim(knowledgePoint), score, trim(grade), trim(scenarioType), trim(scale),
                count, themeOrDefault(), trim(customTheme), trim(forbidden), trim(reference), trim(extra));
    }

    private static void checkLength(List<String> errors, String value, int max, String label) {
        if (!isBlank(value) && codePoints(value) > max) {
            errors.add(label + "不能超过 " + max + " 个字符");
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }
}
