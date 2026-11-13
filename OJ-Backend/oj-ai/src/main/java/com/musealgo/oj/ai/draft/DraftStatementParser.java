package com.musealgo.oj.ai.draft;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把模型输出解析成 {@link DraftContent}。
 * <p>
 * 与旧实现的差别：旧链路只做 {@code JSON.parse} + 逐字段 {@code || ''}，
 * 字段类型不符（例如 tags 是字符串、samples 是字符串数组）会静默变成空值，
 * 教师看到的是「生成成功但内容缺了一半」。这里对每个字段做显式收敛，
 * 并在结构性失败时抛出带原因的异常，让任务以可解释的 FAILED 结束。
 * </p>
 * <p>纯函数、无 Spring 依赖，便于单测覆盖各种畸形输出。</p>
 */
public final class DraftStatementParser {

    /** 宽松解析：容忍模型常见的未转义控制字符、尾随逗号与单引号。 */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .build();

    private static final int MAX_TITLE_CODE_POINTS = 128;
    private static final int MAX_CONTENT = 20_000;
    private static final int MAX_DESC = 4_000;
    private static final int MAX_SAMPLE_TEXT = 8_000;
    private static final int MAX_SAMPLE_EXPLANATION = 500;
    private static final int MAX_SAMPLES = 8;
    private static final int MAX_TAGS = 6;
    private static final int MAX_TAG_LENGTH = 20;
    private static final int MAX_JSON_CANDIDATES = 50;
    private static final int DEFAULT_TIME_LIMIT = 1000;
    private static final int DEFAULT_MEMORY_LIMIT = 512;

    private static final Pattern FIRST_NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

    private static final Set<String> CONTENT_KEYS = Set.of("content", "description", "statement", "problemDescription");
    private static final Set<String> INPUT_KEYS = Set.of("inputDesc", "inputFormat", "inputDescription", "input");
    private static final Set<String> OUTPUT_KEYS = Set.of("outputDesc", "outputFormat", "outputDescription", "output");
    private static final Set<String> HINT_KEYS = Set.of("hint", "tips", "note");

    private DraftStatementParser() {
    }

    /** 解析失败：{@code reason} 直接进入任务的 message，面向教师可读，不包含原始输出片段。 */
    public static final class DraftParseException extends RuntimeException {
        public DraftParseException(String reason) {
            super(reason);
        }
    }

    public static DraftContent parse(String raw, DraftBrief brief) {
        JsonNode root = readObject(raw);
        String title = text(root, "title", "name");
        String content = firstText(root, CONTENT_KEYS);
        if (title.isBlank() && content.isBlank()) {
            throw new DraftParseException("AI 返回的内容里既没有题名也没有题面正文");
        }
        List<String> tags = tags(root);
        List<DraftSample> samples = samples(root);
        int bandMin = brief == null ? 800 : brief.band().min();
        int bandMax = brief == null ? 3000 : brief.band().max();
        int score = clamp(intValue(root, bandMin, "difficultyScore", "difficulty", "score"), 800, 3000);
        int timeLimit = clamp(intValue(root, DEFAULT_TIME_LIMIT, "timeLimit", "timeLimitMs", "time_limit"), 100, 30_000);
        int memoryLimit = clamp(intValue(root, DEFAULT_MEMORY_LIMIT, "memoryLimit", "memoryLimitMb", "memory_limit"), 16, 1024);

        return new DraftContent(
                limit(title.trim(), MAX_TITLE_CODE_POINTS),
                limit(content, MAX_CONTENT),
                limit(firstText(root, INPUT_KEYS), MAX_DESC),
                limit(firstText(root, OUTPUT_KEYS), MAX_DESC),
                limit(firstText(root, HINT_KEYS), MAX_DESC),
                score,
                tags,
                samples,
                timeLimit,
                memoryLimit);
    }

    /**
     * 按花括号配对逐个尝试候选对象。
     * <p>
     * 不用「第一个 { 到最后一个 }」：模型偶尔先输出一段示例 JSON 再输出正文，
     * 那种切法会把两段拼在一起，必然解析失败；逐候选尝试能拿到第一个合法对象。
     * 扫描时跳过字符串内部的括号与转义，避免正文里的 "{" 打乱配对。
     * </p>
     */
    private static JsonNode readObject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new DraftParseException("模型没有返回任何内容");
        }
        String text = stripFence(raw);
        boolean sawBrace = false;
        int attempts = 0;
        for (int start = text.indexOf('{'); start >= 0 && attempts < MAX_JSON_CANDIDATES;
             start = text.indexOf('{', start + 1)) {
            sawBrace = true;
            attempts++;
            int end = matchBrace(text, start);
            if (end < 0) {
                // 这个候选没闭合，但后面的 '{' 可能是另一个更短的对象
                continue;
            }
            try {
                JsonNode node = MAPPER.readTree(text.substring(start, end + 1));
                if (node != null && node.isObject()) {
                    return node;
                }
            } catch (Exception ignored) {
                // 该候选不是合法 JSON，继续尝试下一个 '{'
            }
        }
        throw new DraftParseException(sawBrace
                ? "模型返回的 JSON 无法解析，通常是输出被截断或含未转义字符"
                : "模型返回的内容不是 JSON 对象");
    }

    /** 返回与 {@code start} 处 '{' 配对的 '}' 下标；未闭合返回 -1。 */
    private static int matchBrace(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        char quote = '"';
        for (int index = start; index < text.length(); index++) {
            char current = text.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    inString = false;
                }
                continue;
            }
            if (current == '"' || current == '\'') {
                inString = true;
                quote = current;
            } else if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    /**
     * 去掉 ```json 围栏。
     * <p>
     * 只在「第一个非空行就是围栏」时剥离：否则正文里出现一个 ``` 就会让原先的 toggle 逻辑
     * 把整段内容当围栏外丢掉，只剩最后一行残句，比不剥更糟。
     * </p>
     */
    private static String stripFence(String raw) {
        String text = raw.trim();
        String[] lines = text.split("\n", -1);
        int first = -1;
        for (int index = 0; index < lines.length; index++) {
            if (!lines[index].isBlank()) {
                first = index;
                break;
            }
        }
        if (first < 0 || !lines[first].stripLeading().startsWith("```")) {
            return text;
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int index = first + 1; index < lines.length; index++) {
            if (lines[index].stripLeading().startsWith("```")) {
                break;
            }
            builder.append(lines[index]).append('\n');
        }
        String stripped = builder.toString().trim();
        return stripped.isEmpty() ? text : stripped;
    }

    private static String firstText(JsonNode root, Set<String> keys) {
        for (String key : keys) {
            JsonNode node = root.get(key);
            if (node != null && !node.isNull()) {
                if (node.isValueNode()) {
                    return node.asText("");
                }
                if (node.isArray()) {
                    // 少数模型把输入格式写成字符串数组，逐行拼接比丢弃更接近原意
                    List<String> lines = new ArrayList<>();
                    node.forEach(item -> lines.add(item.asText("")));
                    return String.join("\n", lines);
                }
            }
        }
        return "";
    }

    private static String text(JsonNode root, String... keys) {
        for (String key : keys) {
            JsonNode node = root.get(key);
            if (node != null && node.isValueNode() && !node.isNull()) {
                return node.asText("");
            }
        }
        return "";
    }

    /**
     * 取整数值。
     * <p>
     * 逐位剔除非数字会把 "1.5" 变成 "15"（放大 10 倍），"1000ms" 变成 "1000"（恰好正确）。
     * 这里改为提取第一个数字（含小数点）后四舍五入；时限写成 "1s"/"1 秒" 时按秒换算，
     * 避免把 1 秒存成 1 毫秒。
     * </p>
     */
    private static int intValue(JsonNode root, int fallback, String... keys) {
        for (String key : keys) {
            JsonNode node = root.get(key);
            if (node == null || node.isNull()) {
                continue;
            }
            boolean timeKey = key.replace("_", "").toLowerCase(Locale.ROOT).contains("timelimit");
            if (node.isNumber()) {
                double numeric = node.asDouble(fallback);
                // 时限小于 100 只可能是按秒写的（100ms 以下在题库里无意义），按秒换算
                return (int) Math.round(timeKey && numeric < 100 ? numeric * 1000 : numeric);
            }
            String text = node.asText("").trim();
            Matcher matcher = FIRST_NUMBER.matcher(text);
            if (!matcher.find()) {
                continue;
            }
            try {
                double value = Double.parseDouble(matcher.group());
                if (timeKey && value < 100) {
                    value *= 1000;
                }
                return (int) Math.round(value);
            } catch (NumberFormatException ignored) {
                // 落到下一个候选键或默认值
            }
        }
        return fallback;
    }

    private static List<String> tags(JsonNode root) {
        JsonNode node = root.get("tags");
        if (node == null && root.get("knowledgeTags") != null) {
            node = root.get("knowledgeTags");
        }
        Set<String> unique = new LinkedHashSet<>();
        if (node != null && !node.isNull()) {
            if (node.isArray()) {
                for (JsonNode item : node) {
                    addTag(unique, item.asText(""));
                }
            } else {
                for (String part : node.asText("").split("[,，、/|]")) {
                    addTag(unique, part);
                }
            }
        }
        return List.copyOf(unique);
    }

    private static void addTag(Set<String> unique, String raw) {
        if (raw == null) {
            return;
        }
        String tag = raw.replaceAll("[\\[\\]\"'#]", "").trim();
        if (tag.isEmpty() || unique.size() >= MAX_TAGS) {
            return;
        }
        unique.add(limit(tag, MAX_TAG_LENGTH));
    }

    private static List<DraftSample> samples(JsonNode root) {
        JsonNode node = root.get("samples");
        if (node == null) {
            node = root.get("examples");
        }
        if (node == null) {
            node = root.get("sample");
        }
        if (node == null || node.isNull()) {
            return List.of();
        }
        List<DraftSample> result = new ArrayList<>();
        if (node.isObject()) {
            result.add(sample(node));
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                if (result.size() >= MAX_SAMPLES) {
                    break;
                }
                if (item.isObject()) {
                    result.add(sample(item));
                } else if (item.isArray() && item.size() >= 2) {
                    // 少数模型输出 [["1 2","3"]] 这种二元组
                    result.add(new DraftSample(item.get(0).asText(""), item.get(1).asText(""), ""));
                }
            }
        }
        return List.copyOf(result);
    }

    private static DraftSample sample(JsonNode node) {
        String input = nodeValue(node, "input", "in", "inputData");
        String output = nodeValue(node, "output", "out", "expected", "answer");
        String explanation = nodeValue(node, "explanation", "explain", "note", "description");
        return new DraftSample(limit(input, MAX_SAMPLE_TEXT), limit(output, MAX_SAMPLE_TEXT),
                limit(explanation, MAX_SAMPLE_EXPLANATION));
    }

    private static String nodeValue(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull() && value.isValueNode()) {
                return value.asText("");
            }
        }
        return "";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String limit(String value, int maxCodePoints) {
        if (value == null) {
            return "";
        }
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxCodePoints);
        return value.substring(0, end);
    }
}
