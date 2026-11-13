package com.musealgo.oj.ai.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 把命题简报与既有草稿拼成 user 提示词。
 * <p>
 * 系统提示词（教学约定、JSON 结构）放在 {@code PromptRegistry} 里以便运营按版本调整；
 * 这里只负责「这一题的具体约束」：档位分值区间、样例组数、世界观、禁忌。
 * 把这些写进 user 提示词而不是系统提示词，是为了同一套系统提示能服务所有档位的命题。
 * </p>
 */
public final class DraftPromptBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 受控标签词表：让生成结果的标签能直接匹配题库已有标签，而不是模型自造词。 */
    private static final List<String> TAG_VOCABULARY = List.of(
            "模拟", "枚举", "贪心", "二分查找", "前缀和", "差分", "动态规划", "排序",
            "字符串", "数学", "递归", "栈与队列", "图论入门", "搜索");

    private DraftPromptBuilder() {
    }

    public static String statementPrompt(DraftBrief brief) {
        DraftBrief normalized = brief.normalized();
        StringBuilder text = new StringBuilder();
        text.append("【命题简报】\n");
        text.append("知识点：").append(normalized.knowledgePoint()).append('\n');
        text.append("难度档位：").append(normalized.bandLabel())
                .append("（难度分 ").append(normalized.band().min()).append("~").append(normalized.band().max()).append("）\n");
        appendIfPresent(text, "适用年级", normalized.grade());
        appendIfPresent(text, "场景类型", normalized.scenarioType());
        appendIfPresent(text, "数据规模", normalized.scale());
        text.append("样例组数：").append(normalized.sampleCountOrDefault()).append(" 组\n");
        text.append("命名风格：").append(worldview(normalized)).append('\n');
        appendIfPresent(text, "禁忌要求", normalized.forbidden());
        appendIfPresent(text, "参考题", normalized.reference());
        appendIfPresent(text, "附加要求", normalized.extra());

        text.append("\n【本次硬性要求】\n");
        text.append("1. difficultyScore 必须是 ").append(normalized.band().min()).append('~')
                .append(normalized.band().max()).append(" 之间的整数，且与题目实际难度一致。\n");
        text.append("2. 样例必须恰好 ").append(normalized.sampleCountOrDefault())
                .append(" 组，第 1 组为普通情况，其后至少 1 组覆盖边界；每组都要给出 explanation，")
                .append("并在输出前自己验算一遍输入与输出是否匹配。\n");
        text.append("3. tags 从以下词表中选 1~3 个，不要自造词：").append(String.join("、", TAG_VOCABULARY)).append("。\n");
        text.append("4. content 末尾必须有「数据范围」小节，用 ≤ 写出每个输入量的范围，并给出子任务分值。\n");
        text.append("5. 只输出系统提示规定的 JSON 对象，不要 Markdown 围栏、不要任何解释。\n");
        return text.toString();
    }

    /**
     * 定向重写：只让模型改一个字段，其余字段要求原样返回。
     * <p>
     * 合并由服务端做（{@link DraftContent#replaceField}），所以即使模型顺手改了别的字段也不会污染结果；
     * 这里仍要求「原样返回」，是为了让它把上下文完整读一遍，减少目标字段与其它字段脱节。
     * </p>
     */
    public static String rewritePrompt(DraftBrief brief, DraftContent base, String targetField, String instruction) {
        StringBuilder text = new StringBuilder();
        text.append("【现有题面】\n").append(toJson(base)).append('\n');
        text.append("\n【本次只重写】").append(targetField).append("（").append(fieldLabel(targetField)).append("）\n");
        text.append("【修改要求】").append(instruction == null ? "" : instruction.trim()).append('\n');
        text.append("\n【规则】\n");
        text.append("1. 只有 ").append(targetField).append(" 会被采纳，其余字段必须原样返回，不要顺手改其它字段。\n");
        if ("samples".equals(targetField)) {
            text.append("2. 改写后的样例必须与题面描述和输入输出格式一致，输入输出都要能验算通过。\n");
        } else if ("content".equals(targetField)) {
            text.append("2. 改写后的正文仍要讲清「输入什么、要算什么、输出什么」，并保留「数据范围」小节。\n");
        } else if ("tags".equals(targetField)) {
            text.append("2. 标签仍需从受控词表中选 1~3 个。\n");
        } else {
            text.append("2. 改写后的内容仍需与题面、样例一致，不得引入新的变量或格式。\n");
        }
        text.append("3. 只输出系统提示规定的 JSON 对象。\n");
        if (brief != null) {
            text.append("4. 难度档位保持「").append(brief.bandLabel()).append("」不变。\n");
        }
        return text.toString();
    }

    /** 供「提示词预览」展示，与真实调用使用同一份文本。 */
    public static String fieldLabel(String field) {
        return switch (field == null ? "" : field) {
            case "content" -> "题目描述";
            case "samples" -> "样例";
            case "title" -> "题名";
            case "tags" -> "标签";
            case "hint" -> "提示";
            default -> field;
        };
    }

    private static String worldview(DraftBrief brief) {
        return switch (brief.themeOrDefault()) {
            case "neutral" -> "使用中性命名，不出现睿思星球角色（可用普通的学生、老师、商店等）。";
            case "custom" -> "按教师指定的世界观命名：" + blankTo(brief.customTheme(), "（未填写）");
            default -> "使用睿思星球世界观（小慕、机器人伙伴思思、慕老师、睿思学院、星海市、知识星），"
                    + "角色只做故事背景，不改变算法设定。";
        };
    }

    private static String toJson(DraftContent content) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("title", content.title());
        node.put("content", content.content());
        node.put("inputDesc", content.inputDesc());
        node.put("outputDesc", content.outputDesc());
        node.put("hint", content.hint());
        node.put("difficultyScore", content.difficultyScore());
        ArrayNode tags = node.putArray("tags");
        content.tags().forEach(tags::add);
        ArrayNode samples = node.putArray("samples");
        for (DraftSample sample : content.samples()) {
            ObjectNode item = samples.addObject();
            item.put("input", sample.input());
            item.put("output", sample.output());
            item.put("explanation", sample.explanation());
        }
        node.put("timeLimit", content.timeLimit());
        node.put("memoryLimit", content.memoryLimit());
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            // 序列化失败说明内容里有无法表示的字符；退回逐字段拼接会丢失结构，宁可让调用方失败
            throw new IllegalStateException("题面内容序列化失败", e);
        }
    }

    private static void appendIfPresent(StringBuilder text, String label, String value) {
        if (value != null && !value.isBlank()) {
            text.append(label).append('：').append(value.trim()).append('\n');
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
