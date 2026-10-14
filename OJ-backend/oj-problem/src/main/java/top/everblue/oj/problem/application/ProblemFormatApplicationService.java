package top.everblue.oj.problem.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import top.everblue.oj.api.ai.AiAssistant;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.problem.entity.Problem;
import top.everblue.oj.problem.format.FormatSample;
import top.everblue.oj.problem.format.ProblemFormatApplyRequest;
import top.everblue.oj.problem.format.ProblemFormatItemResult;
import top.everblue.oj.problem.format.ProblemFormatPreviewItem;
import top.everblue.oj.problem.format.ProblemFormatRequest;
import top.everblue.oj.problem.format.ProblemFormatResult;
import top.everblue.oj.problem.format.ProblemFormattedContent;
import top.everblue.oj.problem.infrastructure.ProblemSearchIndexer;
import top.everblue.oj.problem.service.ProblemService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 题目 AI 格式化（含睿思星球 IP 改名）：批量直存 / 单题预览 / 应用预览结果。
 * <p>
 * 从 {@link ProblemManagementApplicationService} 拆出（2026-08-15 大服务类拆分）。
 * 批量格式化每题独立事务（AI 调用在事务外，样例与题面字段原子落库）。
 * </p>
 */
@Service
public class ProblemFormatApplicationService {

    /** 宽松 JSON 解析兜底：容忍模型偶尔输出的未转义控制字符与尾随逗号 */
    private static final ObjectMapper LENIENT_JSON_MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    private static final String FORMAT_SYSTEM_PROMPT = """
            你是专业的算法竞赛题目整理助手，把来自第三方题库的原始英文题面整理成平台的"标准题目结构"并翻译成简体中文。

            输入是一个 JSON 对象，包含 title、content、inputDesc、outputDesc、hint、timeLimitMs、memoryLimitMb、samples。
            原始题面可能存在以下问题，你必须先自行辨别并修复：
            1. 文本错乱或乱序：段落、句子、样例或输入/输出说明可能被拼接、重复、交错或放错字段；
            2. 字段归属错误：输入格式、输出格式、提示、样例可能混在 content 里，需要归位到正确字段；
            3. 格式残缺：HTML 标签、Markdown、LaTeX 可能残缺或混乱，应尽力修复；无法判断归属的残缺内容放入 content；
            4. 时限/内存声明可能写在题面顶部（如 "Time Limit: 2 seconds / Memory Limit: 256 MB"），必须移出 content。

            整理要求：
            - content 只保留题目正文（背景、描述、限制条件、公式等），删除时限/内存声明、"Standard Input/Output" 等标准输入输出说明、输入格式、输出格式、提示和所有样例；
            - inputDesc 输出输入格式，outputDesc 输出输出格式，hint 输出提示；题面里没有对应内容就输出空字符串，绝不编造；
            - timeLimitMs 为题面声明的时限换算成毫秒的整数（如 "2 seconds"→2000，"1s"→1000，"1000ms"→1000），未声明填 1000；
            - memoryLimitMb 为题面声明的内存换算成 MB 的整数（如 "256 megabytes"→256，"1GB"→1024），未声明填 512；
            - samples 提取题面中所有输入输出样例对（包括写在题面底部的），按出现顺序排列；若题面中没有样例，则保留输入中已有的 samples；没有则为空数组。样例内容不要翻译、不要修改格式；
            - 正文、输入格式、输出格式、提示翻译成简体中文；不要翻译代码、样例数据、公式符号和变量名。

            Markdown 与 LaTeX 输出要求（平台前端用 Markdown + KaTeX 渲染，必须严格遵守）：
            - title、content、inputDesc、outputDesc、hint 五个文本字段一律输出规范的 Markdown 格式：使用 # 标题层级（正文用 ## 起）、**加粗**、- 列表、> 引用、`行内代码` 与 ``` 代码块（如输入输出格式示例、数据范围说明）；段落之间用空行分隔；
            - 所有数学公式、算式、不等式、变量关系、单位与数据范围一律用 LaTeX 数学符号：行内公式用 $...$（如 $1 \\le n \\le 10^5$、$a_i$、$O(n \\log n)$），独立公式用 $$...$$；禁止用纯文本拼凑数学符号（如 "<="、"1e5"、"n! " 必须写成 $\\le$、$10^5$、$n!$）；
            - 变量名、函数名、代码标识符用 `行内代码` 包裹而不是数学公式；
            - 样例输出仍保持原样，不套用 Markdown/LaTeX 转换。

            输出必须是合法 JSON 对象，且只能包含 title、content、inputDesc、outputDesc、hint、timeLimitMs、memoryLimitMb、samples 八个字段。
            字段类型：前五个是字符串；timeLimitMs、memoryLimitMb 是整数；samples 是数组，元素为 {"input":"...","output":"..."}。
            例如：{"title":"...","content":"...","inputDesc":"...","outputDesc":"...","hint":"...","timeLimitMs":1000,"memoryLimitMb":512,"samples":[{"input":"...","output":"..."}]}
            只输出 JSON，不要输出 Markdown 代码块、解释或其他任何内容。
            """;

    private static final String FORMAT_REPAIR_PROMPT = """
            你上一条回复不是可解析的合法 JSON。请把其中的题面重新整理成标准结构，
            只输出修正后的合法 JSON 对象，且只能包含 title、content、inputDesc、outputDesc、hint、timeLimitMs、memoryLimitMb、samples 八个字段。
            不要输出 Markdown 代码块、解释或其他任何内容。
            """;

    /** 睿思星球 IP 命名要求（附加到格式化用户提示词末尾，与格式化同一次 AI 调用完成） */
    private static final String FORMAT_IP_RENAME_REQUIREMENT = """
            
            【IP 命名要求】
            将题面（标题、正文、输入格式、输出格式、提示）中的命名实体（人名、地名、机构、物品、
            特殊称谓等专有名词）替换为睿思星球风格名称：优先使用主角小慕、机器人思思、慕老师、
            睿思学院、星海市、知识星、星环空间站、乱码怪，或仿照该风格创造（不超过 6 个字、儿童友好）。
            数学术语、变量名、量词、普通名词不替换；替换后保持语句通顺；不同实体避免重名。
            """;

    private final ProblemService problemService;
    private final ObjectMapper objectMapper;
    private final AiAssistant aiAssistant;
    private final TransactionTemplate transactionTemplate;
    private final ProblemSearchIndexer searchIndexer;
    private final ProblemAccessGuard accessGuard;
    private final ProblemSampleApplicationService sampleApplicationService;

    public ProblemFormatApplicationService(ProblemService problemService,
                                           ObjectMapper objectMapper,
                                           AiAssistant aiAssistant,
                                           PlatformTransactionManager transactionManager,
                                           ProblemSearchIndexer searchIndexer,
                                           ProblemAccessGuard accessGuard,
                                           ProblemSampleApplicationService sampleApplicationService) {
        this.problemService = problemService;
        this.objectMapper = objectMapper;
        this.aiAssistant = aiAssistant;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.searchIndexer = searchIndexer;
        this.accessGuard = accessGuard;
        this.sampleApplicationService = sampleApplicationService;
    }

    /**
     * 批量格式化：AI 整理后直接落库，不预览。
     */
    public ProblemFormatResult formatProblems(ProblemFormatRequest request) {
        SecurityUtils.requireTeacherOrAdmin();
        if (request == null || request.problemIds() == null || request.problemIds().isEmpty()) {
            throw new IllegalArgumentException("请至少选择一道题目");
        }

        List<Long> problemIds = request.problemIds().stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (problemIds.isEmpty()) throw new IllegalArgumentException("题目 ID 无效");
        if (problemIds.size() > 50) throw new IllegalArgumentException("单次最多格式化 50 道题目");

        List<ProblemFormatItemResult> items = new ArrayList<>(problemIds.size());
        int succeeded = 0;
        boolean ipMode = request.ipEnabled();
        for (Long problemId : problemIds) {
            try {
                Problem problem = accessGuard.requireManagedProblem(problemId);
                ProblemFormattedContent formatted = formatWithRetry(problem, ipMode);
                // 每题独立事务：AI 调用在事务外，样例与题面字段原子落库
                transactionTemplate.executeWithoutResult(status -> {
                    applyFormattedFields(problem, formatted);
                    if (!problemService.updateById(problem)) {
                        throw new IllegalStateException("题目保存失败");
                    }
                });
                searchIndexer.indexProblem(problem);
                items.add(ProblemFormatItemResult.success(problemId, problem.getTitle()));
                succeeded++;
            } catch (Exception e) {
                items.add(ProblemFormatItemResult.failure(problemId, rootMessage(e)));
            }
        }
        return new ProblemFormatResult(problemIds.size(), succeeded, problemIds.size() - succeeded, List.copyOf(items));
    }

    /**
     * 单题格式化预览：AI 整理好但尚未写库，返回给前端确认。
     *
     * @param ipMode 是否启用睿思星球 IP 改名（与格式化同一次 AI 调用完成）
     */
    public ProblemFormatPreviewItem previewFormatProblem(Long problemId, boolean ipMode) {
        SecurityUtils.requireTeacherOrAdmin();
        try {
            Problem problem = accessGuard.requireManagedProblem(problemId);
            return ProblemFormatPreviewItem.success(problemId, formatWithRetry(problem, ipMode));
        } catch (Exception e) {
            return ProblemFormatPreviewItem.failure(problemId, rootMessage(e));
        }
    }

    /**
     * 应用单题预览结果：前端回传预览内容，服务端校验后落库。
     */
    public void applyFormattedProblem(ProblemFormatApplyRequest request) {
        SecurityUtils.requireTeacherOrAdmin();
        if (request == null || request.problemId() == null || request.formatted() == null) {
            throw new IllegalArgumentException("格式化结果无效");
        }
        Problem problem = accessGuard.requireManagedProblem(request.problemId());
        applyFormattedFields(problem, request.formatted());
        if (!problemService.updateById(problem)) {
            throw new IllegalStateException("题目保存失败");
        }
        searchIndexer.indexProblem(problem);
    }

    private void applyFormattedFields(Problem problem, ProblemFormattedContent formatted) {
        String title = formatted.title() == null ? "" : formatted.title().trim();
        if (title.isEmpty()) throw new IllegalArgumentException("格式化结果缺少题目名称");

        problem.setTitle(truncateCodePoints(title, 128));
        problem.setContent(sanitizeImportedStatement(nullToEmpty(formatted.content())));
        problem.setInputDesc(sanitizeImportedStatement(nullToEmpty(formatted.inputDesc())));
        problem.setOutputDesc(sanitizeImportedStatement(nullToEmpty(formatted.outputDesc())));
        problem.setHint(sanitizeImportedStatement(nullToEmpty(formatted.hint())));

        // 时限不低于 1000ms；内存不低于 16MB；AI 未给出时用平台默认值 1000ms / 512MB
        int timeLimit = formatted.timeLimitMs() == null || formatted.timeLimitMs() <= 0
                ? 1000 : Math.max(1000, formatted.timeLimitMs());
        int memoryLimit = formatted.memoryLimitMb() == null || formatted.memoryLimitMb() <= 0
                ? 512 : Math.max(16, formatted.memoryLimitMb());
        problem.setTimeLimit(timeLimit);
        problem.setMemoryLimit(memoryLimit);

        sampleApplicationService.replaceFormatSamples(problem.getId(), formatted.samples());
    }

    private ProblemFormattedContent formatWithRetry(Problem problem, boolean ipMode) {
        String source = formatSource(problem);
        if (ipMode) {
            source = source + FORMAT_IP_RENAME_REQUIREMENT;
        }
        if (source.length() > 60_000) {
            throw new IllegalArgumentException("题面过长，无法在一次格式化中安全处理");
        }
        String formatted = aiAssistant.chatJson(FORMAT_SYSTEM_PROMPT, source);
        if (formatted == null) {
            throw new IllegalArgumentException("AI 服务暂不可用，请稍后重试");
        }
        try {
            return parseFormatResponse(formatted);
        } catch (IllegalArgumentException firstFailure) {
            // 首次返回无法解析时，让模型只重新输出一次修正后的 JSON，尽量提高成功率
            String repaired = aiAssistant.chatJson(FORMAT_REPAIR_PROMPT, formatted);
            if (repaired != null) {
                try {
                    return parseFormatResponse(repaired);
                } catch (IllegalArgumentException ignored) {
                    // 仍失败则上报首次失败原因
                }
            }
            throw firstFailure;
        }
    }

    private String formatSource(Problem problem) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("title", nullToEmpty(problem.getTitle()));
            payload.put("content", nullToEmpty(problem.getContent()));
            payload.put("inputDesc", nullToEmpty(problem.getInputDesc()));
            payload.put("outputDesc", nullToEmpty(problem.getOutputDesc()));
            payload.put("hint", nullToEmpty(problem.getHint()));
            payload.put("timeLimitMs", problem.getTimeLimit() == null ? 1000 : problem.getTimeLimit());
            payload.put("memoryLimitMb", problem.getMemoryLimit() == null ? 512 : problem.getMemoryLimit());
            payload.put("samples", sampleApplicationService.getSamples(problem.getId()).stream()
                    .map(s -> Map.of("input", nullToEmpty(s.getInput()), "output", nullToEmpty(s.getOutput())))
                    .toList());
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("题目序列化失败", e);
        }
    }

    private ProblemFormattedContent parseFormatResponse(String response) {
        if (!StringUtils.hasText(response)) throw new IllegalArgumentException("AI 未返回内容");
        for (String candidate : jsonCandidates(response)) {
            Map<String, Object> raw = tryParseJson(candidate);
            if (raw == null) continue;

            Map<String, String> strings = new HashMap<>();
            for (String name : List.of("title", "content", "inputDesc", "outputDesc", "hint")) {
                Object value = raw.get(name);
                if (!(value instanceof String)) throw new IllegalArgumentException("格式化结果缺少字段: " + name);
                strings.put(name, (String) value);
            }
            return new ProblemFormattedContent(
                    strings.get("title"),
                    strings.get("content"),
                    strings.get("inputDesc"),
                    strings.get("outputDesc"),
                    strings.get("hint"),
                    toPositiveInt(raw.get("timeLimitMs")),
                    toPositiveInt(raw.get("memoryLimitMb")),
                    parseSamples(raw.get("samples")));
        }
        throw new IllegalArgumentException("AI 返回内容无法解析为合法 JSON，请重试或人工整理");
    }

    /** 从模型回复中提取可能的 JSON 对象片段（去掉 Markdown 围栏，按括号配对定位） */
    private List<String> jsonCandidates(String response) {
        String stripped = response.replaceAll("(?s)```[a-zA-Z]*\\s*", "").trim();
        List<String> candidates = new ArrayList<>();
        for (int start = stripped.indexOf('{');
             start >= 0 && candidates.size() < 5;
             start = stripped.indexOf('{', start + 1)) {
            String candidate = matchJsonObject(stripped, start);
            if (candidate != null && !candidates.contains(candidate)) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    /**
     * 从 start 位置的 '{' 开始做括号配对（忽略字符串内部与转义字符），
     * 返回配对的完整 JSON 对象；无法配对返回 null。
     */
    private String matchJsonObject(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        return null;
    }

    private Map<String, Object> tryParseJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            try {
                return LENIENT_JSON_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private Integer toPositiveInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.intValue() > 0 ? number.intValue() : null;
        if (value instanceof String text) {
            try {
                int parsed = Integer.parseInt(text.trim());
                return parsed > 0 ? parsed : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private List<FormatSample> parseSamples(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) return List.of();
        if (list.size() > 50) throw new IllegalArgumentException("样例数量超过 50 组，请人工整理");
        List<FormatSample> samples = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Object input = map.get("input");
            Object output = map.get("output");
            samples.add(new FormatSample(
                    input == null ? "" : String.valueOf(input),
                    output == null ? "" : String.valueOf(output)));
        }
        return List.copyOf(samples);
    }

    private String rootMessage(Exception exception) {
        Throwable root = exception;
        while (root.getCause() != null) root = root.getCause();
        return StringUtils.hasText(root.getMessage()) ? root.getMessage() : "格式化失败";
    }

    static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String truncateCodePoints(String value, int maxCodePoints) {
        if (value.codePointCount(0, value.length()) <= maxCodePoints) return value;
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    static String sanitizeImportedStatement(String statement) {
        if (!StringUtils.hasText(statement)) return "";
        org.jsoup.safety.Safelist safelist = org.jsoup.safety.Safelist.relaxed()
                .addTags("table", "thead", "tbody", "tfoot", "tr", "th", "td")
                .addAttributes("th", "colspan", "rowspan")
                .addAttributes("td", "colspan", "rowspan");
        return org.jsoup.Jsoup.clean(statement, safelist);
    }
}
