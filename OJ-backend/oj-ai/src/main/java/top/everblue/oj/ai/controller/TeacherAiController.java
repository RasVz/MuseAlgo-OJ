package top.everblue.oj.ai.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.everblue.oj.ai.AiService;
import top.everblue.oj.ai.TeacherWorkbenchService;
import top.everblue.oj.ai.HintLadderQueryService;
import top.everblue.oj.ai.dto.HintLevelVo;
import top.everblue.oj.ai.entity.AiUsage;
import top.everblue.oj.ai.usage.AiUsageRecorder;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.utils.SecurityUtils;
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
        return chat("teacher-draft", "DRAFT", body.get("requirements"), body.get("difficulty"));
    }

    @Operation(summary = "测试数据生成（需人工复核）")
    @PostMapping("/testcase")
    public Result<String> testcase(@RequestBody Map<String, String> body) {
        return chat("teacher-testcase", "TESTCASE", body.get("statement"), null);
    }

    @Operation(summary = "班级学情解读")
    @PostMapping("/class-insight")
    public Result<String> classInsight(@RequestBody Map<String, String> body) {
        return chat("teacher-insight", "INSIGHT", body.get("statsText"), null);
    }

    // ======================= 命题工作台（B） =======================

    @Operation(summary = "工作台：完整题面生成（结构化 JSON）")
    @PostMapping("/workbench/statement")
    public Result<String> wbStatement(@RequestBody Map<String, String> body) {
        return workbenchJson("teacher-wb-statement", "WB_STATEMENT", buildStatementPrompt(body));
    }

    @Operation(summary = "工作台：标准答案 std 生成（结构化 JSON）")
    @PostMapping("/workbench/std")
    public Result<String> wbStd(@RequestBody Map<String, String> body) {
        return workbenchJson("teacher-wb-std", "WB_STD", buildStdPrompt(body));
    }

    @Operation(summary = "工作台：测试数据生成（结构化 JSON，需人工复核）")
    @PostMapping("/workbench/testdata")
    public Result<String> wbTestdata(@RequestBody Map<String, String> body) {
        return workbenchJson("teacher-wb-testdata", "WB_TESTDATA", buildTestdataPrompt(body));
    }

    @Operation(summary = "工作台：测试数据生成器代码（Python 生成器 + 分布规划，结构化 JSON）")
    @PostMapping("/workbench/generator")
    public Result<String> wbGenerator(@RequestBody Map<String, String> body) {
        return workbenchJson("teacher-wb-generator", "WB_GENERATOR", buildGeneratorPrompt(body));
    }

    @Operation(summary = "工作台：IP 改名（抽取命名实体并建议睿思星球风格新名称）")
    @PostMapping("/workbench/rename")
    public Result<String> wbRename(@RequestBody Map<String, String> body) {
        return workbenchJson("teacher-wb-rename", "WB_RENAME", buildRenamePrompt(body));
    }

    private Result<String> workbenchJson(String toolKey, String scene, String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            return Result.error("请输入内容");
        }
        long startMs = System.currentTimeMillis();
        String raw = teacherWorkbenchService.teacherWorkbenchJson(toolKey, userPrompt);
        JSONObject json = AiService.extractJsonObject(raw);
        if (json == null) {
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
                raw = teacherWorkbenchService.teacherWorkbenchJson(toolKey, userPrompt + repair);
                json = AiService.extractJsonObject(raw);
                if (json != null) {
                    break;
                }
            }
        }
        boolean success = json != null;
        recordUsage(scene, startMs, success, userPrompt, raw);
        if (!success) {
            log.warn("【命题工作台】{} 返回不可解析内容: {}", toolKey,
                    raw == null ? "null" : raw.substring(0, Math.min(raw.length(), 200)));
            return Result.error("生成失败，请稍后重试");
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

    private Result<String> chat(String toolKey, String scene, String input, String extra) {
        if (input == null || input.isBlank()) {
            return Result.error("请输入内容");
        }
        long startMs = System.currentTimeMillis();
        String userPrompt = extra != null && !extra.isBlank()
                ? input + "\n【难度/要求】" + extra : input;
        String answer = teacherWorkbenchService.teacherChat(toolKey, userPrompt);
        boolean success = answer != null && !answer.contains("暂时短路");
        recordUsage(scene, startMs, success, userPrompt, answer);
        return Result.success(answer == null ? "生成失败，请稍后重试" : answer);
    }

    private void recordUsage(String scene, long startMs, boolean success, String prompt, String answer) {
        try {
            int promptTokens = AiService.estimateTokens(prompt);
            int completionTokens = AiService.estimateTokens(answer);
            usageRecorder.record(SecurityUtils.getUserId(), null, scene,
                    promptTokens, completionTokens, true,
                    System.currentTimeMillis() - startMs,
                    success ? AiUsage.STATUS_SUCCESS : AiUsage.STATUS_FAIL,
                    success ? null : "教师 AI 工具调用失败");
        } catch (Exception e) {
            log.warn("【教师AI】用量记账异常: {}", e.getMessage());
        }
    }
}
