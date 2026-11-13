package com.musealgo.oj.ai.draft;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生成题面的确定性质量闸门。
 * <p>
 * 和题库「题面优化」的质量检查不同：那里比较「结果相对原文是否失真」，这里没有原文，
 * 只能判断「一份新题面自身是否可用、是否像给学生看的题」。因此规则集中在结构完整性
 * （标题/正文/样例/格式说明）、可渲染性（围栏与公式闭合）与教学适宜性（暴力词、成句英文）。
 * </p>
 * <p>
 * 纯函数，不依赖 Spring：同样输入永远同样输出，前端看到的清单与服务端拦截依据一致。
 * 只有 {@code ERROR} 会阻断一键落库；教师确认后可越过，越过记录在任务上，便于事后追查。
 * </p>
 */
public final class DraftQualityChecker {

    private static final int MAX_TITLE_CODE_POINTS = 128;
    private static final int MIN_CONTENT_CHARS = 120;
    private static final int MAX_TAGS = 6;
    private static final int MIN_SAMPLES = 2;
    private static final int MAX_SCORE_DRIFT = 200;
    private static final int MAX_SAMPLE_MATCH_LENGTH = 64;

    /** 正文应显式给出数据范围；三种常见写法任一命中即认为已提供。 */
    private static final Pattern RANGE_SECTION = Pattern.compile(
            "(数据范围|数据规模|约束|子任务|对于\\s*\\d+%|≤|＜=|<=|10\\^|10\\s*的\\s*\\d+\\s*次方)");

    private static final Pattern LIMIT_DECLARATION = Pattern.compile(
            "(?i)(time\\s*limit|memory\\s*limit|运行?时限|内存(限制|上限))");

    private static final Pattern HTML_TAG = Pattern.compile(
            "(?i)<(p|div|br|span|table|thead|tbody|tr|td|th|ul|ol|li|pre|code|strong|em|h[1-6])\\b[^>]*>");

    private static final Pattern ENGLISH_WORD = Pattern.compile(
            "(?i)\\b(the|and|you|your|given|follows|constraints|described|should|print|integer|array|each|where)\\b");

    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final Pattern FENCED_BLOCK = Pattern.compile("(?s)```[^\\n]*\\n(.*?)```");

    /**
     * 面向小学生的题面不应出现的词。命中只 WARN：语境可能是「打败乱码怪」这类既定世界观表达，
     * 由教师判断比机器直接拦截更合适。
     */
    private static final List<String> SENSITIVE_WORDS = List.of(
            "杀死", "杀掉", "打死", "流血", "尸体", "自杀", "赌博", "毒品", "枪支", "爆炸", "恐怖袭击");

    private DraftQualityChecker() {
    }

    public static List<DraftIssue> check(DraftContent content, DraftBrief brief) {
        List<DraftIssue> issues = new ArrayList<>();
        if (content == null) {
            issues.add(DraftIssue.error("CONTENT_MISSING", "content", "没有可审阅的题面内容"));
            return List.copyOf(issues);
        }
        checkTitle(content, issues);
        checkBody(content, issues);
        checkDescriptions(content, issues);
        checkSamples(content, issues);
        checkTags(content, issues);
        checkDifficulty(content, brief, issues);
        checkTone(content, issues);
        return List.copyOf(issues);
    }

    public static boolean hasBlockingIssue(List<DraftIssue> issues) {
        return issues != null && issues.stream().anyMatch(issue -> issue.severity() == DraftSeverity.ERROR);
    }

    /** 只检查本次真正写入的字段：只采纳标题时不应被正文的阻断问题拦住。 */
    public static List<DraftIssue> blockingIssues(List<DraftIssue> issues, Set<String> fields) {
        if (issues == null || fields == null || fields.isEmpty()) {
            return List.of();
        }
        return issues.stream()
                .filter(issue -> issue.severity() == DraftSeverity.ERROR && fields.contains(issue.field()))
                .toList();
    }

    private static void checkTitle(DraftContent content, List<DraftIssue> issues) {
        String title = trimToEmpty(content.title());
        if (title.isEmpty()) {
            issues.add(DraftIssue.error("TITLE_EMPTY", "title", "题名为空，无法作为题目保存"));
            return;
        }
        int codePoints = title.codePointCount(0, title.length());
        if (codePoints > MAX_TITLE_CODE_POINTS) {
            issues.add(DraftIssue.error("TITLE_TOO_LONG", "title",
                    "题名 " + codePoints + " 字，超过题库 " + MAX_TITLE_CODE_POINTS + " 字上限，保存时会被截断"));
        }
    }

    private static void checkBody(DraftContent content, List<DraftIssue> issues) {
        String body = trimToEmpty(content.content());
        if (body.isEmpty()) {
            issues.add(DraftIssue.error("CONTENT_EMPTY", "content", "题目正文为空，无法保存"));
            return;
        }
        int bodyLength = body.codePointCount(0, body.length());
        if (bodyLength < MIN_CONTENT_CHARS) {
            issues.add(DraftIssue.warn("CONTENT_TOO_SHORT", "content",
                    "正文仅 " + bodyLength + " 字，可能没讲清「输入什么、要算什么、输出什么」"));
        }
        if (!RANGE_SECTION.matcher(body).find()) {
            issues.add(DraftIssue.warn("CONTENT_MISSING_RANGE", "content",
                    "正文没有找到数据范围或约束说明，学生无法判断规模，评测也会缺少边界依据"));
        }
        boolean fencesBalanced = countFences(body) % 2 == 0;
        if (!fencesBalanced) {
            issues.add(DraftIssue.error("CODE_FENCE_UNBALANCED", "content",
                    "代码围栏 ``` 未成对闭合，后面的正文会被整体渲染成代码块"));
        }
        // 围栏未闭合时 stripCode 失效（成对正则匹配不到），此时再报 $ 不配对只是同一个原因的重复噪音
        if (fencesBalanced && countUnescapedDollar(stripCode(body)) % 2 != 0) {
            issues.add(DraftIssue.warn("LATEX_UNBALANCED", "content",
                    "数学公式的 $ 未成对闭合，公式会渲染成原文"));
        }
        if (LIMIT_DECLARATION.matcher(body).find()) {
            issues.add(DraftIssue.warn("LIMIT_DECLARATION_LEFT", "content",
                    "正文里出现了时限/内存声明；这两项由题目设置决定，写在正文里容易与实际配置不一致"));
        }
        if (HTML_TAG.matcher(body).find()) {
            issues.add(DraftIssue.info("HTML_TAG_RESIDUE", "content", "正文包含原始 HTML 标签，将按富文本渲染"));
        }
        reportSampleDuplicatedInContent(body, content.samples(), issues);
    }

    private static void checkDescriptions(DraftContent content, List<DraftIssue> issues) {
        if (trimToEmpty(content.inputDesc()).isEmpty()) {
            issues.add(DraftIssue.warn("INPUT_DESC_EMPTY", "inputDesc", "缺少输入格式说明，学生不知道要读几行"));
        }
        if (trimToEmpty(content.outputDesc()).isEmpty()) {
            issues.add(DraftIssue.warn("OUTPUT_DESC_EMPTY", "outputDesc", "缺少输出格式说明，学生不知道要输出什么"));
        }
    }

    private static void checkSamples(DraftContent content, List<DraftIssue> issues) {
        List<DraftSample> samples = content.samples();
        if (samples.isEmpty()) {
            issues.add(DraftIssue.error("SAMPLE_EMPTY", "samples", "没有任何样例，学生无法自测，题目不适合发布"));
            return;
        }
        if (samples.size() < MIN_SAMPLES) {
            issues.add(DraftIssue.warn("SAMPLE_TOO_FEW", "samples",
                    "只有 " + samples.size() + " 组样例，建议至少 2 组覆盖边界情况"));
        }
        Set<String> seen = new HashSet<>();
        int reported = 0;
        for (int index = 0; index < samples.size() && reported < 3; index++) {
            DraftSample sample = samples.get(index);
            if (sample == null) {
                continue;
            }
            if (sample.input().isBlank() && sample.output().isBlank()) {
                issues.add(DraftIssue.warn("SAMPLE_EMPTY_PAIR", "samples",
                        "第 " + (index + 1) + " 组样例的输入与输出都为空"));
                reported++;
                continue;
            }
            String key = sample.input().strip() + '\u0000' + sample.output().strip();
            if (!seen.add(key)) {
                issues.add(DraftIssue.warn("SAMPLE_DUPLICATED", "samples",
                        "第 " + (index + 1) + " 组样例与前面完全重复"));
                reported++;
            }
        }
    }

    private static void checkTags(DraftContent content, List<DraftIssue> issues) {
        List<String> tags = content.tags();
        if (tags.isEmpty()) {
            issues.add(DraftIssue.warn("TAGS_EMPTY", "tags", "没有知识点标签，题库筛选与推荐会缺少依据"));
            return;
        }
        if (tags.size() > MAX_TAGS) {
            issues.add(DraftIssue.warn("TAGS_TOO_MANY", "tags",
                    "标签 " + tags.size() + " 个，建议保留 1~" + MAX_TAGS + " 个最能代表解法的"));
        }
    }

    private static void checkDifficulty(DraftContent content, DraftBrief brief, List<DraftIssue> issues) {
        if (brief == null || content.difficultyScore() == null) {
            return;
        }
        int score = content.difficultyScore();
        DraftBrief.Band band = brief.band();
        int drift = score < band.min() ? band.min() - score : score > band.max() ? score - band.max() : 0;
        if (drift > MAX_SCORE_DRIFT) {
            issues.add(DraftIssue.warn("DIFFICULTY_OUT_OF_BAND", "difficultyScore",
                    "生成难度分 " + score + " 与所选档位「" + band.label() + "」（" + band.min() + "~" + band.max()
                            + "）相差较大，请确认难度是否符合预期"));
        }
    }

    private static void checkTone(DraftContent content, List<DraftIssue> issues) {
        String body = content.content() + "\n" + content.hint();
        for (String word : SENSITIVE_WORDS) {
            if (body.contains(word)) {
                issues.add(DraftIssue.warn("SENSITIVE_WORD", "content",
                        "题面出现「" + word + "」，面向小学生请确认语境是否合适"));
                break;
            }
        }
        String stripped = stripCode(body);
        if (CJK.matcher(stripped).find() && countMatches(ENGLISH_WORD, stripped) >= 3) {
            issues.add(DraftIssue.warn("ENGLISH_RESIDUE", "content", "中文题面里仍有成句英文，可能是翻译未完成"));
        }
    }

    /** 正文里留着与样例完全相同的代码块时，前台会把同一段内容展示两次。 */
    private static void reportSampleDuplicatedInContent(String body, List<DraftSample> samples, List<DraftIssue> issues) {
        if (samples == null || samples.isEmpty()) {
            return;
        }
        Set<String> blocks = new HashSet<>();
        Matcher matcher = FENCED_BLOCK.matcher(body);
        while (matcher.find()) {
            String block = matcher.group(1) == null ? "" : matcher.group(1).trim();
            if (block.length() >= 4) {
                blocks.add(block);
            }
        }
        if (blocks.isEmpty()) {
            return;
        }
        for (DraftSample sample : samples) {
            if (sample == null) {
                continue;
            }
            String input = sample.input().trim();
            if (input.length() >= 4 && input.length() <= MAX_SAMPLE_MATCH_LENGTH && blocks.contains(input)) {
                issues.add(DraftIssue.warn("SAMPLE_IN_CONTENT", "content",
                        "正文代码块与样例输入重复，题面详情页会把同一段内容展示两次"));
                return;
            }
        }
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

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
