package com.musealgo.oj.problem.application;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.problem.error.ProblemErrorCode;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import com.musealgo.oj.api.ai.AiAssistant;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.problem.entity.Problem;
import com.musealgo.oj.problem.entity.ProblemSample;
import com.musealgo.oj.problem.format.FormatSample;
import com.musealgo.oj.problem.format.IssueSeverity;
import com.musealgo.oj.problem.format.ProblemFormatApplyRequest;
import com.musealgo.oj.problem.format.ProblemFormatApplyResult;
import com.musealgo.oj.problem.format.ProblemFormatPreviewItem;
import com.musealgo.oj.problem.format.ProblemFormattedContent;
import com.musealgo.oj.problem.format.ProblemStatementHasher;
import com.musealgo.oj.problem.format.ProblemStatementSnapshot;
import com.musealgo.oj.problem.format.StatementIssue;
import com.musealgo.oj.problem.format.quality.StatementQualityChecker;
import com.musealgo.oj.problem.infrastructure.ProblemSearchIndexer;
import com.musealgo.oj.problem.service.ProblemService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 题目 AI 整理（含睿思星球 IP 改名）：单题预览 / 字段级应用 / 批量任务的单题生成。
 * <p>
 * 与旧实现的三点差异：
 * </p>
 * <ol>
 *   <li>预览额外返回原文快照、质量问题与 {@code baseHash}；应用时校验哈希，
 *       避免「预览后题目被改，应用静默覆盖」；</li>
 *   <li>应用支持字段白名单，前端可以只采纳其中几项；</li>
 *   <li>样例不会被空结果清空——模型漏输出时保留原样例，把删除决定交回人工编辑。</li>
 * </ol>
 */
@Service
public class ProblemFormatApplicationService {

    /** 宽松 JSON 解析兜底：容忍模型偶尔输出的未转义控制字符与尾随逗号 */
    private static final ObjectMapper LENIENT_JSON_MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    /** 可应用的字段，顺序即响应中 appliedFields 的顺序 */
    private static final List<String> ALL_FIELDS = List.of(
            "title", "content", "inputDesc", "outputDesc", "hint", "timeLimit", "memoryLimit", "samples");
    private static final Set<String> ALL_FIELD_SET = Set.copyOf(ALL_FIELDS);

    private static final int MAX_TITLE_CODE_POINTS = 128;
    private static final int MAX_SOURCE_CHARS = 60_000;
    /**
     * 首次调用已经耗时超过这个预算时不再追问「修一下 JSON」：一次模型调用最长 150s，
     * 两次串起来就顶到网关 300s 读超时。宁可让教师重试，也不要留下一个必然 504 的请求。
     */
    private static final long REPAIR_BUDGET_MS = 120_000L;

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
            - content 中不要保留 "Input Format"、"Output Format"、"Sample Input"、"Example 1" 这类章节标题；
            - inputDesc 输出输入格式，outputDesc 输出输出格式，hint 输出提示；题面里没有对应内容就输出空字符串，绝不编造；
            - timeLimitMs 为题面声明的时限换算成毫秒的整数（如 "2 seconds"→2000，"1s"→1000，"1000ms"→1000），未声明填 1000；
            - memoryLimitMb 为题面声明的内存换算成 MB 的整数（如 "256 megabytes"→256，"1GB"→1024），未声明填 512；
            - samples 提取题面中所有输入输出样例对（包括写在题面底部的），按出现顺序排列；
            - samples 是硬约束：输入里已有样例时，输出必须包含同样组数的样例，一组都不能少；只允许修正明显的串行/错位，
              不允许删减、合并、概括或用"..."省略。若题面中没有样例，则原样保留输入中的 samples；没有则为空数组。
              样例内容不要翻译、不要改写数值与格式；
            - 正文、输入格式、输出格式、提示翻译成简体中文；不要翻译代码、样例数据、公式符号和变量名。

            Markdown 与 LaTeX 输出要求（平台前端用 Markdown + KaTeX 渲染，必须严格遵守）：
            - title、content、inputDesc、outputDesc、hint 五个文本字段一律输出规范的 Markdown 格式：使用 # 标题层级（正文用 ## 起）、**加粗**、- 列表、> 引用、`行内代码` 与 ``` 代码块（如输入输出格式示例、数据范围说明）；段落之间用空行分隔；
            - 所有数学公式、算式、不等式、变量关系、单位与数据范围一律用 LaTeX 数学符号：行内公式用 $...$（如 $1 \\le n \\le 10^5$、$a_i$、$O(n \\log n)$），独立公式用 $$...$$；禁止用纯文本拼凑数学符号（如 "<="、"1e5"、"n! " 必须写成 $\\le$、$10^5$、$n!$）；
            - 每个 $ 必须成对闭合，每个 ``` 代码块必须闭合；
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
            samples 必须保留输入中已有的全部样例组数，不得删减。
            不要输出 Markdown 代码块、解释或其他任何内容。
            """;

    /** 睿思星球 IP 命名要求（附加到整理输入末尾，与整理同一次 AI 调用完成） */
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
    private final JdbcTemplate jdbcTemplate;

    public ProblemFormatApplicationService(ProblemService problemService,
                                           ObjectMapper objectMapper,
                                           AiAssistant aiAssistant,
                                           PlatformTransactionManager transactionManager,
                                           ProblemSearchIndexer searchIndexer,
                                           ProblemAccessGuard accessGuard,
                                           ProblemSampleApplicationService sampleApplicationService,
                                           JdbcTemplate jdbcTemplate) {
        this.problemService = problemService;
        this.objectMapper = objectMapper;
        this.aiAssistant = aiAssistant;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.searchIndexer = searchIndexer;
        this.accessGuard = accessGuard;
        this.sampleApplicationService = sampleApplicationService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 一次整理产物：结果 + 原文快照 + 质量检查 + 基线哈希。 */
    public record Proposal(ProblemFormattedContent formatted,
                           ProblemStatementSnapshot source,
                           List<StatementIssue> issues,
                           String baseHash) {
    }

    /**
     * 生成整理提案但不写库：批量任务的 worker 与单题预览共用这条路径，
     * 保证「自动应用」与「人工审阅」看到的是同一套结果与质量判定。
     */
    public Proposal generateProposal(Problem problem, boolean ipMode) {
        return generateProposal(problem, ipMode, SecurityUtils.getUserId());
    }

    /**
     * @param actorUserId 记录 AI 用量归属的用户；后台任务传任务创建人，请求内调用传当前登录用户
     */
    public Proposal generateProposal(Problem problem, boolean ipMode, Long actorUserId) {
        ProblemStatementSnapshot source = snapshot(problem);
        String baseHash = ProblemStatementHasher.hash(source);
        ProblemFormattedContent raw = formatWithRetry(source, ipMode, actorUserId);
        // 出口统一消毒：预览会被前端当富文本渲染，写入也会再消毒一次。
        // 在这里做完，前端看到的内容与最终落库的内容才一致。
        ProblemFormattedContent formatted = sanitizeFormatted(raw);
        List<StatementIssue> issues = new ArrayList<>(StatementQualityChecker.check(source, formatted));
        if (sanitizationChanged(raw, formatted)) {
            issues.add(StatementIssue.warn("UNSAFE_HTML_STRIPPED", "content",
                    "整理结果含被安全策略移除的标签或属性，已清除；请确认正文没有因此缺内容"));
        }
        return new Proposal(formatted, source, List.copyOf(issues), baseHash);
    }

    private ProblemFormattedContent sanitizeFormatted(ProblemFormattedContent raw) {
        return new ProblemFormattedContent(
                raw.title(),
                StatementSanitizer.sanitize(nullToEmpty(raw.content())),
                StatementSanitizer.sanitize(nullToEmpty(raw.inputDesc())),
                StatementSanitizer.sanitize(nullToEmpty(raw.outputDesc())),
                StatementSanitizer.sanitize(nullToEmpty(raw.hint())),
                raw.timeLimitMs(),
                raw.memoryLimitMb(),
                raw.samples());
    }

    private boolean sanitizationChanged(ProblemFormattedContent raw, ProblemFormattedContent sanitized) {
        return !nullToEmpty(raw.content()).equals(sanitized.content())
                || !nullToEmpty(raw.inputDesc()).equals(sanitized.inputDesc())
                || !nullToEmpty(raw.outputDesc()).equals(sanitized.outputDesc())
                || !nullToEmpty(raw.hint()).equals(sanitized.hint());
    }

    /**
     * 单题整理预览：AI 整理好但尚未写库。
     * <p>失败以 {@code success=false} 返回而不是抛异常，前端据此在弹窗内展示原因。</p>
     */
    public ProblemFormatPreviewItem previewFormatProblem(Long problemId, boolean ipMode) {
        SecurityUtils.requireTeacherOrAdmin();
        try {
            Problem problem = accessGuard.requireManagedProblem(problemId);
            Proposal proposal = generateProposal(problem, ipMode);
            return ProblemFormatPreviewItem.success(problemId, proposal.formatted(), proposal.source(),
                    proposal.baseHash(), proposal.issues());
        } catch (Exception e) {
            return ProblemFormatPreviewItem.failure(problemId, rootMessage(e), publicErrorCode(e));
        }
    }

    /**
     * 应用单题整理结果。
     * <p>
     * 事务内先对该题加行锁再校验哈希：两个人同时应用同一份预览时，只有第一个能通过，
     * 第二个会拿到 409 而不是覆盖对方刚写入的内容。
     * </p>
     */
    public ProblemFormatApplyResult applyFormattedProblem(ProblemFormatApplyRequest request) {
        SecurityUtils.requireTeacherOrAdmin();
        return applyInternal(request, true);
    }

    /**
     * 批量任务专用入口：权限在创建任务时已按创建人的真实身份校验完毕，
     * worker 线程没有请求上下文，因此这里不再读 SecurityContext。
     * 行锁与哈希校验保持不变——这两条防的是并发覆盖，与调用来源无关。
     */
    public ProblemFormatApplyResult applyFormattedProposal(Long problemId, String baseHash,
                                                           ProblemFormattedContent formatted) {
        return applyInternal(new ProblemFormatApplyRequest(problemId, baseHash, null, formatted), false);
    }

    private ProblemFormatApplyResult applyInternal(ProblemFormatApplyRequest request, boolean checkAccess) {
        if (request == null || request.problemId() == null || request.formatted() == null) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "整理结果无效");
        }
        Set<String> fields = normalizeApplyFields(request.fields());

        ApplyOutcome outcome = transactionTemplate.execute(status -> {
            lockProblemRow(request.problemId());
            Problem problem = checkAccess
                    ? accessGuard.requireManagedProblem(request.problemId())
                    : problemService.getById(request.problemId());
            if (problem == null) {
                throw new BusinessException(ProblemErrorCode.PROBLEM_NOT_FOUND, "题目不存在");
            }
            ProblemStatementSnapshot current = snapshot(problem);
            if (!StringUtils.hasText(request.baseHash())) {
                // 没有基线就无法判断结果是否基于最新内容：直接拒绝，避免绕过并发保护
                throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID,
                        "缺少原文基线，请重新整理后再应用");
            }
            if (!ProblemStatementHasher.hash(current).equals(request.baseHash())) {
                throw new BusinessException(ProblemErrorCode.PROBLEM_CONTENT_CHANGED,
                        "题目内容在整理之后已被修改，本次结果已过期，请重新整理");
            }
            // 服务端二次闸门：前端可以取消勾选某个字段，但不能带着该字段的阻断问题强行写入
            List<StatementIssue> issues = StatementQualityChecker.check(current, request.formatted());
            if (StatementQualityChecker.hasBlockingIssue(issues, fields)) {
                StatementIssue blocking = issues.stream()
                        .filter(issue -> issue.severity() == IssueSeverity.ERROR && fields.contains(issue.field()))
                        .findFirst().orElseThrow();
                throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID,
                        "存在阻断问题，无法应用：" + blocking.message());
            }
            List<String> applied = applyFields(problem, request.formatted(), fields);
            if (applied.isEmpty()) {
                throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "没有可应用的字段");
            }
            if (!problemService.updateById(problem)) {
                throw new IllegalStateException("题目保存失败");
            }
            return new ApplyOutcome(problem, applied);
        });
        if (outcome == null) {
            throw new IllegalStateException("题目保存失败");
        }
        searchIndexer.indexProblem(outcome.problem());
        return new ProblemFormatApplyResult(request.problemId(), outcome.appliedFields(),
                ProblemStatementHasher.hash(snapshot(outcome.problem())));
    }

    private record ApplyOutcome(Problem problem, List<String> appliedFields) {
    }

    /**
     * worker 侧读取题目：不经过 SecurityUtils（后台线程没有请求上下文）。
     * 权限已在创建任务时按创建人的真实身份校验，这里只保证题目仍然存在。
     */
    public Problem requireProblemForJob(Long problemId) {
        Problem problem = problemService.getById(problemId);
        if (problem == null) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_NOT_FOUND, "题目不存在");
        }
        return problem;
    }

    /** 读取原文快照：预览对照、哈希基线、质量检查都基于这一份数据。 */
    public ProblemStatementSnapshot snapshot(Problem problem) {
        List<FormatSample> samples = new ArrayList<>();
        if (problem.getId() != null) {
            for (ProblemSample sample : sampleApplicationService.getSamples(problem.getId())) {
                samples.add(new FormatSample(nullToEmpty(sample.getInput()), nullToEmpty(sample.getOutput())));
            }
        }
        return new ProblemStatementSnapshot(
                nullToEmpty(problem.getTitle()),
                nullToEmpty(problem.getContent()),
                nullToEmpty(problem.getInputDesc()),
                nullToEmpty(problem.getOutputDesc()),
                nullToEmpty(problem.getHint()),
                problem.getTimeLimit() == null ? 1000 : problem.getTimeLimit(),
                problem.getMemoryLimit() == null ? 512 : problem.getMemoryLimit(),
                List.copyOf(samples));
    }

    private Set<String> normalizeApplyFields(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            // 兼容旧调用方：不传 fields 视为全量
            return new LinkedHashSet<>(ALL_FIELDS);
        }
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        for (String field : requested) {
            if (field == null) {
                continue;
            }
            String normalized = field.trim();
            if (ALL_FIELD_SET.contains(normalized)) {
                fields.add(normalized);
            }
        }
        if (fields.isEmpty()) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "没有可应用的字段");
        }
        return fields;
    }

    private List<String> applyFields(Problem problem, ProblemFormattedContent formatted, Set<String> fields) {
        List<String> applied = new ArrayList<>(fields.size());

        if (fields.contains("title")) {
            String title = formatted.title() == null ? "" : formatted.title().trim();
            if (title.isEmpty()) {
                throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "整理结果缺少题目名称");
            }
            problem.setTitle(truncateCodePoints(title, MAX_TITLE_CODE_POINTS));
            applied.add("title");
        }
        if (fields.contains("content")) {
            String content = nullToEmpty(formatted.content());
            if (content.isBlank()) {
                throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "整理结果正文为空");
            }
            problem.setContent(StatementSanitizer.sanitize(content));
            applied.add("content");
        }
        if (fields.contains("inputDesc")) {
            problem.setInputDesc(StatementSanitizer.sanitize(nullToEmpty(formatted.inputDesc())));
            applied.add("inputDesc");
        }
        if (fields.contains("outputDesc")) {
            problem.setOutputDesc(StatementSanitizer.sanitize(nullToEmpty(formatted.outputDesc())));
            applied.add("outputDesc");
        }
        if (fields.contains("hint")) {
            problem.setHint(StatementSanitizer.sanitize(nullToEmpty(formatted.hint())));
            applied.add("hint");
        }
        if (fields.contains("timeLimit")) {
            // 时限不低于 1000ms；内存不低于 16MB；AI 未给出时用平台默认值 1000ms / 512MB
            int timeLimit = formatted.timeLimitMs() == null || formatted.timeLimitMs() <= 0
                    ? 1000 : Math.max(1000, formatted.timeLimitMs());
            problem.setTimeLimit(timeLimit);
            applied.add("timeLimit");
        }
        if (fields.contains("memoryLimit")) {
            int memoryLimit = formatted.memoryLimitMb() == null || formatted.memoryLimitMb() <= 0
                    ? 512 : Math.max(16, formatted.memoryLimitMb());
            problem.setMemoryLimit(memoryLimit);
            applied.add("memoryLimit");
        }
        if (fields.contains("samples")) {
            List<FormatSample> samples = formatted.samples() == null ? List.of() : formatted.samples();
            // 兜底：空数组或「全空对」都不写入，避免把学生可见的真实样例清掉。
            // 正常情况下 apply 前的质量闸门已经用 SAMPLES_LOST 拦住了这类结果，
            // 这里只是不依赖闸门顺序的最后一道保险。
            if (isEmptySampleList(samples) && !sampleApplicationService.getSamples(problem.getId()).isEmpty()) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .warn("整理结果为空样例，已保留原样例 problemId={}", problem.getId());
            } else {
                sampleApplicationService.replaceFormatSamples(problem.getId(), samples);
                applied.add("samples");
            }
        }
        return applied;
    }

    private static boolean isEmptySampleList(List<FormatSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return true;
        }
        for (FormatSample sample : samples) {
            if (sample == null) {
                continue;
            }
            if (!nullToEmpty(sample.input()).isBlank() || !nullToEmpty(sample.output()).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private void lockProblemRow(Long problemId) {        List<Long> locked = jdbcTemplate.queryForList(
                "SELECT id FROM problem WHERE id = ? FOR UPDATE", Long.class, problemId);
        if (locked.isEmpty()) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_NOT_FOUND, "题目不存在");
        }
    }

    private ProblemFormattedContent formatWithRetry(ProblemStatementSnapshot snapshot, boolean ipMode, Long actorUserId) {
        long startedAt = System.currentTimeMillis();
        String source = formatSource(snapshot);
        if (ipMode) {
            source = source + FORMAT_IP_RENAME_REQUIREMENT;
        }
        if (source.length() > MAX_SOURCE_CHARS) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID,
                    "题面超过 " + MAX_SOURCE_CHARS + " 字符，单次整理无法安全处理，请先拆分或精简正文");
        }
        String formatted = chatJson(actorUserId, FORMAT_SYSTEM_PROMPT, source);
        if (formatted == null) {
            throw new BusinessException(ProblemErrorCode.AI_UNAVAILABLE, "AI 服务暂不可用，请稍后重试");
        }
        try {
            return parseFormatResponse(formatted);
        } catch (IllegalArgumentException | BusinessException firstFailure) {
            if (System.currentTimeMillis() - startedAt > REPAIR_BUDGET_MS) {
                // 已经耗掉大半超时预算，再追问一次必然超过网关读超时；直接让教师重试
                throw firstFailure;
            }
            // 首次返回无法解析时，让模型只重新输出一次修正后的 JSON，尽量提高成功率
            String repaired = chatJson(actorUserId, FORMAT_REPAIR_PROMPT, formatted);
            if (repaired != null) {
                try {
                    return parseFormatResponse(repaired);
                } catch (IllegalArgumentException | BusinessException ignored) {
                    // 仍失败则上报首次失败原因
                }
            }
            throw firstFailure;
        }
    }

    private String chatJson(Long actorUserId, String systemPrompt, String userPrompt) {
        return actorUserId == null
                ? aiAssistant.chatJson(systemPrompt, userPrompt)
                : aiAssistant.chatJsonForUser(actorUserId, systemPrompt, userPrompt);
    }

    private String formatSource(ProblemStatementSnapshot snapshot) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("title", snapshot.title());
            payload.put("content", snapshot.content());
            payload.put("inputDesc", snapshot.inputDesc());
            payload.put("outputDesc", snapshot.outputDesc());
            payload.put("hint", snapshot.hint());
            payload.put("timeLimitMs", snapshot.timeLimitMs());
            payload.put("memoryLimitMb", snapshot.memoryLimitMb());
            payload.put("samples", snapshot.samples().stream()
                    .map(s -> Map.of("input", nullToEmpty(s.input()), "output", nullToEmpty(s.output())))
                    .toList());
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("题目序列化失败", e);
        }
    }

    private ProblemFormattedContent parseFormatResponse(String response) {
        if (!StringUtils.hasText(response)) {
            throw new BusinessException(ProblemErrorCode.AI_RESPONSE_INVALID, "AI 未返回内容");
        }
        for (String candidate : jsonCandidates(response)) {
            Map<String, Object> raw = tryParseJson(candidate);
            if (raw == null) {
                continue;
            }
            Map<String, String> strings = new HashMap<>();
            for (String name : List.of("title", "content", "inputDesc", "outputDesc", "hint")) {
                Object value = raw.get(name);
                if (!(value instanceof String)) {
                    throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "整理结果缺少字段: " + name);
                }
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
        throw new BusinessException(ProblemErrorCode.AI_RESPONSE_INVALID, "AI 返回内容无法解析为合法 JSON，请重试或人工整理");
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
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
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
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue() > 0 ? number.intValue() : null;
        }
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
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        if (list.size() > 50) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "样例数量超过 50 组，请人工整理");
        }
        List<FormatSample> samples = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object input = map.get("input");
            Object output = map.get("output");
            samples.add(new FormatSample(
                    input == null ? "" : String.valueOf(input),
                    output == null ? "" : String.valueOf(output)));
        }
        return List.copyOf(samples);
    }

    private String publicErrorCode(Exception exception) {
        return exception instanceof com.musealgo.oj.common.exception.BusinessException business
                ? business.getErrorCode().key() : "INTERNAL_ERROR";
    }

    private String rootMessage(Exception exception) {
        if (exception instanceof com.musealgo.oj.common.exception.BusinessException business) {
            return business.getMessage();
        }
        org.slf4j.LoggerFactory.getLogger(getClass()).error("整理单项失败", exception);
        return "处理失败，请稍后重试或联系管理员";
    }

    static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String truncateCodePoints(String value, int maxCodePoints) {
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    static boolean isKnownField(String field) {
        return field != null && ALL_FIELD_SET.contains(field.trim());
    }
}
