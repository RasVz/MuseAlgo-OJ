package com.musealgo.oj.ai.controller;

import com.musealgo.oj.common.exception.ErrorResponses;
import com.musealgo.oj.common.exception.CommonErrorCode;
import com.musealgo.oj.ai.error.AiErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.musealgo.oj.ai.AiService;
import com.musealgo.oj.ai.TeacherWorkbenchService;
import com.musealgo.oj.ai.HintLadderQueryService;
import com.musealgo.oj.ai.dto.AiCallSource;
import com.musealgo.oj.ai.dto.AiCompletionResult;
import com.musealgo.oj.ai.dto.AiTokenUsage;
import com.musealgo.oj.ai.dto.HintLevelVo;
import com.musealgo.oj.ai.entity.AiUsage;
import com.musealgo.oj.ai.usage.AiUsageRecorder;
import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.common.utils.SecurityUtils;
import cn.hutool.json.JSONObject;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 教师侧 AI 助手（A4/B）：命题工作台（题面 / std / 测试数据 / IP 改名）+ 学情解读。 */
@Slf4j
@Tag(name = "教师 AI 助手", description = "教师教学辅助工具")
@RestController
@RequestMapping("/ai/teacher")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
public class TeacherAiController {

    private final TeacherWorkbenchService teacherWorkbenchService;
    private final AiUsageRecorder usageRecorder;
    private final HintLadderQueryService hintLadderQueryService;

    /**
     * 班级 × 题目的 Hint Ladder 解锁情况：每位学生的最高解锁级别。
     * <p>
     * 权限：@PreAuthorize 已限定教师/管理员，班级归属（班主任本人）由
     * {@link HintLadderQueryService} 校验。级别 = Redis 当前值 ∪ 轨迹表历史，取高。
     * </p>
     */
    @Operation(summary = "查询班级内每位学生对某题的 Hint Ladder 解锁级别")
    @GetMapping("/hint/levels")
    public Result<List<HintLevelVo>> hintLevels(@RequestParam Long classId,
                                                @RequestParam Long problemId) {
        return Result.success(hintLadderQueryService.classHintLevels(classId, problemId));
    }

    @Operation(summary = "题面草稿生成")
    @PostMapping("/draft-problem")
    public Result<String> draftProblem(@RequestBody Map<String, String> body) {
        Result<String> invalid = validate(body,
                new FieldRule("requirements", "命题要求", MAX_PROMPT, true),
                new FieldRule("difficulty", "难度", MAX_SHORT, false));
        if (invalid != null) return invalid;
        return chat("teacher-draft", "DRAFT", body.get("requirements"), body.get("difficulty"));
    }

    @Operation(summary = "测试数据生成（需人工复核）")
    @PostMapping("/testcase")
    public Result<String> testcase(@RequestBody Map<String, String> body) {
        Result<String> invalid = validate(body, new FieldRule("statement", "题面", MAX_STATEMENT, true));
        if (invalid != null) return invalid;
        return chat("teacher-testcase", "TESTCASE", body.get("statement"), null);
    }

    @Operation(summary = "班级学情解读")
    @PostMapping("/class-insight")
    public Result<String> classInsight(@RequestBody Map<String, String> body) {
        Result<String> invalid = validate(body, new FieldRule("statsText", "学情数据", MAX_STATEMENT, true));
        if (invalid != null) return invalid;
        return chat("teacher-insight", "INSIGHT", body.get("statsText"), null);
    }

    // ======================= 命题工作台（B） =======================

    @Operation(summary = "工作台：完整题面生成（结构化 JSON）")
    @PostMapping("/workbench/statement")
    public Result<String> wbStatement(@RequestBody Map<String, String> body) {
        Result<String> invalid = validate(body,
                new FieldRule("knowledgePoint", "知识点", MAX_SHORT, true),
                new FieldRule("difficulty", "难度", MAX_SHORT, false),
                new FieldRule("scenario", "场景设定", MAX_MEDIUM, false),
                new FieldRule("scale", "数据规模", MAX_SHORT, false),
                new FieldRule("extra", "附加要求", MAX_MEDIUM, false));
        if (invalid != null) return invalid;
        return workbenchJson("teacher-wb-statement", "WB_STATEMENT", buildStatementPrompt(body));
    }

    @Operation(summary = "工作台：标准答案 std 生成（结构化 JSON）")
    @PostMapping("/workbench/std")
    public Result<String> wbStd(@RequestBody Map<String, String> body) {
        Result<String> invalid = validate(body,
                new FieldRule("statement", "题面", MAX_STATEMENT, true),
                new FieldRule("title", "标题", MAX_SHORT, false));
        if (invalid != null) return invalid;
        return workbenchJson("teacher-wb-std", "WB_STD", buildStdPrompt(body));
    }

    @Operation(summary = "工作台：测试数据生成（结构化 JSON，需人工复核）")
    @PostMapping("/workbench/testdata")
    public Result<String> wbTestdata(@RequestBody Map<String, String> body) {
        Result<String> invalid = validateStatementInputs(body);
        if (invalid == null) invalid = validateCaseCount(body, 1, 20);
        if (invalid != null) return invalid;
        return workbenchJson("teacher-wb-testdata", "WB_TESTDATA", buildTestdataPrompt(body));
    }

    @Operation(summary = "工作台：测试数据生成器代码（Python 生成器 + 分布规划，结构化 JSON）")
    @PostMapping("/workbench/generator")
    public Result<String> wbGenerator(@RequestBody Map<String, String> body) {
        Result<String> invalid = validateStatementInputs(body);
        if (invalid == null) invalid = validateCaseCount(body, 1, 50);
        if (invalid != null) return invalid;
        return workbenchJson("teacher-wb-generator", "WB_GENERATOR", buildGeneratorPrompt(body));
    }

    @Operation(summary = "工作台：IP 改名（抽取命名实体并建议睿思星球风格新名称）")
    @PostMapping("/workbench/rename")
    public Result<String> wbRename(@RequestBody Map<String, String> body) {
        Result<String> invalid = validate(body,
                new FieldRule("statement", "题面", MAX_STATEMENT, true),
                new FieldRule("ipUniverse", "IP 宇宙", MAX_SHORT, false),
                new FieldRule("mappings", "映射关系", MAX_PROMPT, false));
        if (invalid != null) return invalid;
        return workbenchJson("teacher-wb-rename", "WB_RENAME", buildRenamePrompt(body));
    }

    private Result<String> workbenchJson(String toolKey, String scene, String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            return ErrorResponses.failure(AiErrorCode.AI_INPUT_INVALID, "请输入内容");
        }
        long startMs = System.currentTimeMillis();
        AiCompletionResult call = teacherWorkbenchService.teacherWorkbenchJsonDetailed(toolKey, userPrompt);
        String raw = call.content();
        JSONObject json = AiService.extractJsonObject(raw);
        if (json == null && call.success()) {
            // 首次输出超长截断/死循环/格式损坏：追加针对性约束重试（最多两次修复）
            String[] repairs = {
                    "\n\n【重要】你上一次的输出因过长被截断或格式损坏。请重新输出精简的 JSON："
                            + "每个字段值不超过 200 字符，总输出不超过 1200 字符；"
                            + "字符串输入一律用 10~30 个字符的代表性串；不要重复内容、不要多余说明、不要 Markdown 围栏。",
                    "\n\n【再次警告】你上一次仍然输出了超长或重复的内容。这次必须做到："
                            + "恰好 6 组、每组 input/output 不超过 80 字符、总输出不超过 800 字符，"
                            + "立即结束，不要生成任何超出要求的内容。"
            };
            for (String repair : repairs) {
                call = teacherWorkbenchService.teacherWorkbenchJsonDetailed(toolKey, userPrompt + repair);
                raw = call.content();
                json = AiService.extractJsonObject(raw);
                if (json != null || !call.success()) {
                    break;
                }
            }
        }
        boolean success = json != null;
        recordUsage(scene, startMs, success, userPrompt, raw, call.source(), call.usage());
        if (!success) {
            log.warn("【命题工作台】{} 返回不可解析内容: {}", toolKey,
                    raw == null ? "null" : raw.substring(0, Math.min(raw.length(), 200)));
            if (!call.success()) {
                return upstreamFailure(call);
            }
            return ErrorResponses.failure(AiErrorCode.AI_RESPONSE_INVALID);
        }
        return Result.success(json.toString());
    }

    private String buildStatementPrompt(Map<String, String> body) {
        StringBuilder sb = new StringBuilder("【命题要求】\n");
        appendIfPresent(sb, "知识点", body.get("knowledgePoint"));
        appendIfPresent(sb, "难度", body.get("difficulty"));
        appendIfPresent(sb, "场景设定", body.get("scenario"));
        appendIfPresent(sb, "数据规模", body.get("scale"));
        appendIfPresent(sb, "附加要求", body.get("extra"));
        if ("off".equals(body.get("ipMode"))) {
            sb.append("命名风格：不使用睿思星球世界观，采用普通中性命名。\n");
        } else {
            sb.append("命名风格：使用睿思星球世界观（默认）。\n");
        }
        return sb.toString();
    }

    private String buildStdPrompt(Map<String, String> body) {
        StringBuilder sb = new StringBuilder("【题目】\n");
        sb.append("标题：").append(body.getOrDefault("title", "")).append("\n\n");
        sb.append(body.getOrDefault("statement", ""));
        return sb.toString();
    }

    private String buildTestdataPrompt(Map<String, String> body) {
        StringBuilder sb = new StringBuilder("【题目】\n");
        sb.append("标题：").append(body.getOrDefault("title", "")).append("\n\n");
        sb.append(body.getOrDefault("statement", "")).append("\n\n");
        appendIfPresent(sb, "输入格式", body.get("inputDesc"));
        appendIfPresent(sb, "输出格式", body.get("outputDesc"));
        appendIfPresent(sb, "数据范围与约束", body.get("constraints"));
        String count = body.getOrDefault("caseCount", "6");
        sb.append("【生成组数】").append(count).append(" 组\n");
        return sb.toString();
    }

    private String buildGeneratorPrompt(Map<String, String> body) {
        StringBuilder sb = new StringBuilder("【题目】\n");
        sb.append("标题：").append(body.getOrDefault("title", "")).append("\n\n");
        sb.append(body.getOrDefault("statement", "")).append("\n\n");
        appendIfPresent(sb, "输入格式", body.get("inputDesc"));
        appendIfPresent(sb, "输出格式", body.get("outputDesc"));
        appendIfPresent(sb, "数据范围与约束", body.get("constraints"));
        String count = body.getOrDefault("caseCount", "30");
        sb.append("【生成组数】").append(count).append(" 组\n");
        return sb.toString();
    }

    private String buildRenamePrompt(Map<String, String> body) {
        StringBuilder sb = new StringBuilder("【题目题面】\n");
        sb.append(body.getOrDefault("statement", "")).append("\n\n");
        appendIfPresent(sb, "IP 宇宙", body.get("ipUniverse"));
        String mappings = body.get("mappings");
        if (hasText(mappings)) {
            sb.append("【用户指定映射】（original→ipName 已给出时优先采用）\n").append(mappings).append('\n');
        }
        return sb.toString();
    }

    private static void appendIfPresent(StringBuilder sb, String label, String value) {
        if (hasText(value)) {
            sb.append(label).append('：').append(value).append('\n');
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    // ======================= 输入边界 =======================

    /** 题面/学情正文上限：约等于一道长题的完整文本。 */
    private static final int MAX_STATEMENT = 8000;
    /** 提示词类输入上限（命题要求、映射表）。 */
    private static final int MAX_PROMPT = 4000;
    /** 中等长度字段上限（场景、附加要求、输入输出格式）。 */
    private static final int MAX_MEDIUM = 1000;
    /** 短字段上限（知识点、难度、标题、数据规模）。 */
    private static final int MAX_SHORT = 200;

    private record FieldRule(String field, String label, int maxLength, boolean required) {
    }

    /** 校验必填与长度；返回错误响应，全部通过返回 null。长度按码点计，避免中文/emoji 提前截断。 */
    private static Result<String> validate(Map<String, String> body, FieldRule... rules) {
        if (body == null) {
            return ErrorResponses.failure(AiErrorCode.AI_INPUT_INVALID, "请求内容不能为空");
        }
        for (FieldRule rule : rules) {
            String value = body.get(rule.field());
            if (!hasText(value)) {
                if (rule.required()) {
                    return ErrorResponses.failure(AiErrorCode.AI_INPUT_INVALID, "请填写" + rule.label());
                }
                continue;
            }
            if (value.codePointCount(0, value.length()) > rule.maxLength()) {
                return ErrorResponses.failure(AiErrorCode.AI_INPUT_INVALID,
                        rule.label() + "不能超过 " + rule.maxLength() + " 个字符");
            }
        }
        return null;
    }

    /** 测试数据类工具共用的题面字段边界。 */
    private static Result<String> validateStatementInputs(Map<String, String> body) {
        return validate(body,
                new FieldRule("statement", "题面", MAX_STATEMENT, true),
                new FieldRule("title", "标题", MAX_SHORT, false),
                new FieldRule("inputDesc", "输入格式", MAX_MEDIUM, false),
                new FieldRule("outputDesc", "输出格式", MAX_MEDIUM, false),
                new FieldRule("constraints", "数据范围与约束", MAX_MEDIUM, false));
    }

    /** 生成组数必须是范围内的整数，避免把异常值拼进提示词。 */
    private static Result<String> validateCaseCount(Map<String, String> body, int min, int max) {
        String raw = body == null ? null : body.get("caseCount");
        if (!hasText(raw)) return null;
        int count;
        try {
            count = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return ErrorResponses.failure(AiErrorCode.AI_INPUT_INVALID, "生成组数必须是整数");
        }
        if (count < min || count > max) {
            return ErrorResponses.failure(AiErrorCode.AI_INPUT_INVALID,
                    "生成组数需在 " + min + "~" + max + " 之间");
        }
        return null;
    }

    private Result<String> chat(String toolKey, String scene, String input, String extra) {
        if (input == null || input.isBlank()) {
            return ErrorResponses.failure(AiErrorCode.AI_INPUT_INVALID, "请输入内容");
        }
        long startMs = System.currentTimeMillis();
        String userPrompt = extra != null && !extra.isBlank()
                ? input + "\n【难度/要求】" + extra : input;
        AiCompletionResult call = teacherWorkbenchService.teacherChatDetailed(toolKey, userPrompt);
        String answer = call.success() ? call.content() : null;
        recordUsage(scene, startMs, call.success(), userPrompt, answer, call.source(), call.usage());
        if (answer == null || answer.isBlank()) {
            // 失败必须回错误码：把「生成失败」当成功文本返回，前端会把它填进题面编辑器，
            // 使用者无法区分模型没答和题目内容本身。
            log.warn("【教师AI】{} 生成失败 type={}", toolKey, call.errorType());
            return upstreamFailure(call);
        }
        return Result.success(answer);
    }

    /** 把上游失败类型映射为对外错误码，供非结构化与结构化工具共用。 */
    private static Result<String> upstreamFailure(AiCompletionResult call) {
        if ("INVALID_RESPONSE".equals(call.errorType()) || "RESPONSE_TOO_LARGE".equals(call.errorType())) {
            return ErrorResponses.failure(AiErrorCode.AI_RESPONSE_INVALID);
        }
        return ErrorResponses.failure("TIMEOUT".equals(call.errorType())
                ? CommonErrorCode.UPSTREAM_TIMEOUT : AiErrorCode.AI_UPSTREAM_FAILED);
    }

    private void recordUsage(String scene, long startMs, boolean success, String prompt, String answer,
                             AiCallSource source, AiTokenUsage usage) {
        try {
            int promptTokens;
            int completionTokens;
            boolean estimated;
            if (usage != null) {
                promptTokens = usage.promptTokens();
                completionTokens = usage.completionTokens();
                estimated = false;
            } else {
                promptTokens = AiService.estimateTokens(prompt);
                completionTokens = AiService.estimateTokens(answer);
                estimated = true;
            }
            usageRecorder.record(SecurityUtils.getUserId(), null, scene,
                    promptTokens, completionTokens, estimated,
                    System.currentTimeMillis() - startMs,
                    success ? AiUsage.STATUS_SUCCESS : AiUsage.STATUS_FAIL,
                    success ? null : "教师 AI 工具调用失败", source);
        } catch (Exception e) {
            log.warn("【教师AI】用量记账异常: {}", e.getMessage());
        }
    }
}
