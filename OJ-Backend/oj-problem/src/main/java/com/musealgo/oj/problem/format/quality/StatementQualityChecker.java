package com.musealgo.oj.problem.format.quality;

import com.musealgo.oj.problem.format.FormatSample;
import com.musealgo.oj.problem.format.IssueSeverity;
import com.musealgo.oj.problem.format.ProblemFormattedContent;
import com.musealgo.oj.problem.format.ProblemStatementSnapshot;
import com.musealgo.oj.problem.format.StatementIssue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 题面质量检查：把「AI 整理结果相对原文是否可信」变成可枚举、可单测的规则集。
 * <p>
 * 纯函数，不依赖 Spring 与数据库。只有 {@code ERROR} 会阻断前端「应用并保存」；
 * {@code WARN} 需要教师确认；{@code INFO} 只做提示。这样既不把可疑结果静默写库，
 * 也不会因为无害差异（例如原文本来就没有输入描述）卡住正常流程。
 * </p>
 */
public final class StatementQualityChecker {

    private static final int MAX_TITLE_CODE_POINTS = 128;
    private static final int MIN_CONTENT_CHARS = 80;
    /** 原文足够长时，结果缩到 40% 以下基本只可能是被截断，而不是精炼。 */
    private static final int CONTENT_LOSS_BASELINE = 400;
    private static final double CONTENT_LOSS_RATIO = 0.4;
    private static final int MIN_TIME_LIMIT_MS = 1000;
    private static final int MAX_TIME_LIMIT_MS = 30_000;
    private static final int MIN_MEMORY_LIMIT_MB = 16;
    private static final int MAX_MEMORY_LIMIT_MB = 1024;
    private static final int MAX_SAMPLE_DIFF_REPORTS = 3;
    private static final int MAX_EXAMPLE_MATCH_LENGTH = 64;

    /** 时限/内存声明：整理后必须从正文移出，落到结构化字段。 */
    private static final Pattern LIMIT_DECLARATION = Pattern.compile(
            "(?i)(time\\s*limit\\s*[:：]?|memory\\s*limit\\s*[:：]?|运行?时限\\s*[:：]|内存(限制|上限)\\s*[:：])");

    /** 独立的输入/输出/样例章节标题：这些内容应分别落到 inputDesc / outputDesc / samples。 */
    private static final Pattern IO_SECTION = Pattern.compile(
            "(?im)^[\\s>#*+\\-]*"
                    + "(input\\s*format|output\\s*format|input|output|sample\\s*input|sample\\s*output"
                    + "|example\\s*\\d*|输入格式|输出格式|输入|输出|样例输入|样例输出|输入样例|输出样例)"
                    + "[\\s*:：]*$");

    private static final Pattern HTML_TAG = Pattern.compile(
            "(?i)<(p|div|br|span|table|thead|tbody|tr|td|th|ul|ol|li|pre|code|strong|em|h[1-6])\\b[^>]*>");

    /** 英文虚词：中文正文里整句未翻译时会成簇出现。 */
    private static final Pattern ENGLISH_WORD = Pattern.compile(
            "(?i)\\b(the|and|you|your|given|follows|constraints|described|should|print|integer|array|each|where)\\b");

    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    private static final Pattern FENCED_BLOCK = Pattern.compile("(?s)```[^\\n]*\\n(.*?)```");

    private StatementQualityChecker() {
    }

    public static List<StatementIssue> check(ProblemStatementSnapshot source, ProblemFormattedContent formatted) {
        List<StatementIssue> issues = new ArrayList<>();
        if (formatted == null) {
            issues.add(StatementIssue.error("FORMATTED_MISSING", "content", "AI 未产出可用的题面结果"));
            return List.copyOf(issues);
        }
        checkTitle(formatted, issues);
        checkContent(source, formatted, issues);
        checkDescriptions(formatted, issues);
        checkLimits(formatted, issues);
        checkSamples(source, formatted, issues);
        return List.copyOf(issues);
    }

    /** 只要存在 ERROR 级问题就不允许直接落库。 */
    public static boolean hasBlockingIssue(List<StatementIssue> issues) {
        return issues != null && issues.stream().anyMatch(issue -> issue.severity() == IssueSeverity.ERROR);
    }

    /**
     * 只检查「本次真正要写入的字段」：应用 title 时不该被 samples 的阻断问题拦住，
     * 反之亦然。apply 用它做服务端二次闸门，避免前端绕过质量检查。
     */
    public static boolean hasBlockingIssue(List<StatementIssue> issues, Set<String> appliedFields) {
        if (issues == null || appliedFields == null) {
            return false;
        }
        return issues.stream().anyMatch(issue -> issue.severity() == IssueSeverity.ERROR
                && appliedFields.contains(issue.field()));
    }

    /**
     * 自动应用（无人值守写库）时的样例风险：数量减少、重复、空对都意味着可见样例被破坏。
     * <p>
     * 刻意不包含 {@code SAMPLE_CONTENT_CHANGED}：原文样例本身就错乱时，模型修正内容正是
     * 这个功能的目的之一，把它也算作风险会让自动应用几乎永远不触发。该提醒仍会在
     * 人工审阅时展示。
     * </p>
     */
    public static boolean hasSampleRisk(List<StatementIssue> issues) {
        if (issues == null) {
            return false;
        }
        return issues.stream().anyMatch(issue -> switch (issue.code()) {
            case "SAMPLES_LOST", "SAMPLES_REDUCED", "SAMPLE_DUPLICATED", "SAMPLE_EMPTY_PAIR" -> true;
            default -> false;
        });
    }

    private static void checkTitle(ProblemFormattedContent formatted, List<StatementIssue> issues) {
        String title = trimToEmpty(formatted.title());
        if (title.isEmpty()) {
            issues.add(StatementIssue.error("TITLE_EMPTY", "title", "题名为空，无法保存"));
            return;
        }
        int codePoints = title.codePointCount(0, title.length());
        if (codePoints > MAX_TITLE_CODE_POINTS) {
            issues.add(StatementIssue.error("TITLE_TOO_LONG", "title",
                    "题名 " + codePoints + " 字，超过 " + MAX_TITLE_CODE_POINTS + " 字上限，保存时会被截断"));
        }
    }

    private static void checkContent(ProblemStatementSnapshot source, ProblemFormattedContent formatted,
                                     List<StatementIssue> issues) {
        String content = trimToEmpty(formatted.content());
        if (content.isEmpty()) {
            issues.add(StatementIssue.error("CONTENT_EMPTY", "content", "题目正文为空，无法保存"));
            return;
        }
        if (content.length() < MIN_CONTENT_CHARS) {
            issues.add(StatementIssue.warn("CONTENT_TOO_SHORT", "content",
                    "正文仅 " + content.length() + " 字，明显短于常规题面，请确认内容没有被截断"));
        }
        if (source != null && source.content() != null
                && source.content().length() >= CONTENT_LOSS_BASELINE
                && content.length() < source.content().length() * CONTENT_LOSS_RATIO) {
            issues.add(StatementIssue.error("CONTENT_LOST", "content",
                    "正文由 " + source.content().length() + " 字缩到 " + content.length()
                            + " 字，疑似被截断；如确属精简请在编辑页手动处理"));
        }
        if (LIMIT_DECLARATION.matcher(content).find()) {
            issues.add(StatementIssue.error("LIMIT_DECLARATION_LEFT", "content",
                    "正文仍含时限/内存声明，应移出正文并写入时限与内存字段"));
        }
        if (IO_SECTION.matcher(content).find()) {
            issues.add(StatementIssue.warn("IO_SECTION_LEFT", "content",
                    "正文仍含「输入/输出/样例」章节标题，这些内容应拆到输入描述、输出描述与样例"));
        }
        String stripped = stripCode(content);
        if (countUnescapedDollar(stripped) % 2 != 0) {
            issues.add(StatementIssue.warn("LATEX_UNBALANCED", "content",
                    "数学公式的 $ 未成对闭合，公式可能渲染异常"));
        }
        if (countFences(content) % 2 != 0) {
            // 未闭合的围栏会把后面整段正文吞进代码块，属于结构性破坏
            issues.add(StatementIssue.error("CODE_FENCE_UNBALANCED", "content",
                    "代码块 ``` 未成对闭合，后面的正文会被整体渲染成代码，请先修好"));
        }
        if (CJK.matcher(content).find() && countMatches(ENGLISH_WORD, stripped) >= 3) {
            issues.add(StatementIssue.warn("ENGLISH_RESIDUE", "content",
                    "中文正文里仍有成句英文，可能未完成翻译"));
        }
        if (HTML_TAG.matcher(content).find()) {
            issues.add(StatementIssue.info("HTML_TAG_RESIDUE", "content",
                    "正文含原始 HTML 标签，将按富文本渲染；若来自 FPS 导入属正常情况"));
        }
        if (source != null) {
            reportDuplicatedSample(content, source.samples(), issues);
        }
    }

    private static void checkDescriptions(ProblemFormattedContent formatted, List<StatementIssue> issues) {
        if (trimToEmpty(formatted.inputDesc()).isEmpty()) {
            issues.add(StatementIssue.info("INPUT_DESC_EMPTY", "inputDesc", "未提取到输入描述"));
        }
        if (trimToEmpty(formatted.outputDesc()).isEmpty()) {
            issues.add(StatementIssue.info("OUTPUT_DESC_EMPTY", "outputDesc", "未提取到输出描述"));
        }
    }

    private static void checkLimits(ProblemFormattedContent formatted, List<StatementIssue> issues) {
        Integer time = formatted.timeLimitMs();
        if (time == null) {
            issues.add(StatementIssue.info("TIME_LIMIT_DEFAULT", "timeLimit", "题面未声明时限，将使用平台默认 1000ms"));
        } else if (time < MIN_TIME_LIMIT_MS || time > MAX_TIME_LIMIT_MS) {
            issues.add(StatementIssue.warn("TIME_LIMIT_RANGE", "timeLimit",
                    "时限 " + time + "ms 超出常见范围 " + MIN_TIME_LIMIT_MS + "~" + MAX_TIME_LIMIT_MS + "ms"));
        }
        Integer memory = formatted.memoryLimitMb();
        if (memory == null) {
            issues.add(StatementIssue.info("MEMORY_LIMIT_DEFAULT", "memoryLimit", "题面未声明内存，将使用平台默认 512MB"));
        } else if (memory < MIN_MEMORY_LIMIT_MB || memory > MAX_MEMORY_LIMIT_MB) {
            issues.add(StatementIssue.warn("MEMORY_LIMIT_RANGE", "memoryLimit",
                    "内存 " + memory + "MB 超出常见范围 " + MIN_MEMORY_LIMIT_MB + "~" + MAX_MEMORY_LIMIT_MB + "MB"));
        }
    }

    private static void checkSamples(ProblemStatementSnapshot source, ProblemFormattedContent formatted,
                                     List<StatementIssue> issues) {
        List<FormatSample> sourceSamples = source == null || source.samples() == null ? List.of() : source.samples();
        List<FormatSample> resultSamples = formatted.samples() == null ? List.of() : formatted.samples();

        if (!sourceSamples.isEmpty() && resultSamples.isEmpty()) {
            issues.add(StatementIssue.error("SAMPLES_LOST", "samples",
                    "原文有 " + sourceSamples.size() + " 组样例，整理结果一组都没有；已阻止直接覆盖"));
            return;
        }
        if (resultSamples.size() < sourceSamples.size()) {
            issues.add(StatementIssue.warn("SAMPLES_REDUCED", "samples",
                    "样例由 " + sourceSamples.size() + " 组减少为 " + resultSamples.size() + " 组"));
        }

        Set<String> known = new HashSet<>();
        for (FormatSample sample : sourceSamples) {
            known.add(sampleKey(sample));
        }
        Set<String> seen = new HashSet<>();
        int reported = 0;
        for (int index = 0; index < resultSamples.size() && reported < MAX_SAMPLE_DIFF_REPORTS; index++) {
            FormatSample sample = resultSamples.get(index);
            if (sample == null) {
                continue;
            }
            String key = sampleKey(sample);
            if (!seen.add(key)) {
                issues.add(StatementIssue.warn("SAMPLE_DUPLICATED", "samples",
                        "第 " + (index + 1) + " 组样例与前面重复"));
                reported++;
                continue;
            }
            boolean bothBlank = isBlank(sample.input()) && isBlank(sample.output());
            if (bothBlank) {
                issues.add(StatementIssue.warn("SAMPLE_EMPTY_PAIR", "samples",
                        "第 " + (index + 1) + " 组样例的输入与输出都为空"));
                reported++;
                continue;
            }
            if (!sourceSamples.isEmpty() && !known.contains(key)) {
                issues.add(StatementIssue.warn("SAMPLE_CONTENT_CHANGED", "samples",
                        "第 " + (index + 1) + " 组样例与原文不一致，请确认是修复了错乱还是被改写"));
                reported++;
            }
        }
    }

    /**
     * 正文里如果还留着与样例完全相同的代码块，前台会重复展示「样例」两次。
     * 只比较长度足够的整块内容，避免把正文里的短数字误判成样例。
     */
    private static void reportDuplicatedSample(String content, List<FormatSample> samples, List<StatementIssue> issues) {
        if (samples == null || samples.isEmpty()) {
            return;
        }
        Set<String> blocks = new HashSet<>();
        Matcher matcher = FENCED_BLOCK.matcher(content);
        while (matcher.find()) {
            String block = matcher.group(1) == null ? "" : matcher.group(1).trim();
            if (block.length() >= 4) {
                blocks.add(block);
            }
        }
        if (blocks.isEmpty()) {
            return;
        }
        for (FormatSample sample : samples) {
            if (sample == null) {
                continue;
            }
            String input = trimToEmpty(sample.input());
            if (input.length() >= 4 && input.length() <= MAX_EXAMPLE_MATCH_LENGTH && blocks.contains(input)) {
                issues.add(StatementIssue.warn("SAMPLE_DUPLICATED_IN_CONTENT", "content",
                        "正文代码块与样例输入重复，前台会把同一段内容展示两次"));
                return;
            }
        }
    }

    private static String sampleKey(FormatSample sample) {
        if (sample == null) {
            return "\u0000\u0000";
        }
        return normalizeText(sample.input()) + '\u0000' + normalizeText(sample.output());
    }

    /** 逐行去尾部空白并去首尾空行：行尾空格与结尾换行不应被当成内容变更。 */
    private static String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        String[] lines = value.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            builder.append(line.stripTrailing()).append('\n');
        }
        return builder.toString().strip();
    }

    private static String stripCode(String text) {
        String withoutFences = FENCED_BLOCK.matcher(text).replaceAll("");
        return withoutFences.replaceAll("`[^`\\n]*`", "");
    }

    private static int countUnescapedDollar(String text) {
        int count = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) != '$') {
                continue;
            }
            if (index > 0 && text.charAt(index - 1) == '\\') {
                continue;
            }
            count++;
        }
        return count;
    }

    private static int countFences(String text) {
        int count = 0;
        for (String line : text.split("\n", -1)) {
            if (line.stripLeading().startsWith("```")) {
                count++;
            }
        }
        return count;
    }

    private static int countMatches(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find() && count < 100) {
            count++;
        }
        return count;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /** 供前端字段定位使用的字段名集合（保持与 apply 的 fields 白名单一致）。 */
    public static final class Fields {
        public static final String TITLE = "title";
        public static final String CONTENT = "content";
        public static final String INPUT_DESC = "inputDesc";
        public static final String OUTPUT_DESC = "outputDesc";
        public static final String HINT = "hint";
        public static final String TIME_LIMIT = "timeLimit";
        public static final String MEMORY_LIMIT = "memoryLimit";
        public static final String SAMPLES = "samples";

        private Fields() {
        }
    }

    /** 字段名 → 中文名，供日志与后端错误文案复用。 */
    public static final Map<String, String> FIELD_LABELS = Map.of(
            Fields.TITLE, "题名",
            Fields.CONTENT, "正文",
            Fields.INPUT_DESC, "输入描述",
            Fields.OUTPUT_DESC, "输出描述",
            Fields.HINT, "提示",
            Fields.TIME_LIMIT, "时间限制",
            Fields.MEMORY_LIMIT, "内存限制",
            Fields.SAMPLES, "样例");
}
