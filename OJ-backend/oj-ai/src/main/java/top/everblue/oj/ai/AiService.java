package top.everblue.oj.ai;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.everblue.oj.ai.gateway.AiGateway;
import top.everblue.oj.ai.gateway.PromptRegistry;
import top.everblue.oj.ai.dto.AiAskOutcome;
import top.everblue.oj.ai.guard.AiContestGuard;
import top.everblue.oj.ai.quota.AiQuotaService;
import top.everblue.oj.ai.entity.AiUsage;
import top.everblue.oj.api.ai.AiAssistant;
import top.everblue.oj.ai.DeepSeekClient;
import top.everblue.oj.ai.hint.HintLadderGuide;

import java.util.function.Consumer;

/**
 * AI 助教编排层：负责拼装对话、读写历史、注入检索资料、决定降级策略。
 * <p>
 * 职责边界：
 * <ul>
 *   <li>模型传输 + 供应商切换/缓存 → {@link AiGateway}</li>
 *   <li>提示词模板（版本化）→ {@link PromptRegistry}</li>
 *   <li>会话历史 → {@link AiChatSessionService}</li>
 *   <li>检索增强（RAG）→ {@link RagContextService}</li>
 *   <li>本类只做"拼装 + 编排"</li>
 * </ul>
 */
@Slf4j
@Service
public class AiService implements AiAssistant {

    private static final String API_FAIL_FALLBACK = "糟糕，教练的大脑暂时短路了，请稍后再试一下吧！";

    /**
     * 系统提示词：角色 + 工作流程 + 输出格式 + 禁止事项。
     * 题目与代码是"请求级快照"，每次请求刷新；对话历史与检索资料单独拼装。
     */
    private static final String TUTOR_SYSTEM_PROMPT = """
            你是资深信奥教练，辅导中小学生 C++，语气亲切鼓励、不说教。

            【工作流程】每次回答按固定顺序：
            1. 先诊断：学生卡在哪个环节（读题 / 思路 / 代码 / 调试）。
            2. 按学生状态选择模式：
               - 引导模式（默认）：用 1~2 个苏格拉底式问题引导学生。
               - 讲解模式（学生说"不会 / 不知道 / 讲详细点"或明显沮丧时）：
                 直接讲解核心思路推导，再给关键代码片段。
            3. 输出前自检一遍【禁止事项】。

            【输出格式】
            - 正文不超过 300 字（代码块不计入）。
            - 代码用 ```cpp 围栏，关键行必须带注释。
            - 代码必须使用 4 空格缩进，禁止使用 Tab。
            - 运算符（+ - * / = == != < > <= >= && || 等）两侧必须加空格；
              逗号与分号后必须加空格；for/if/while 等关键字与括号之间必须加空格。
            - 引导模式下，问题不超过 2 个。

            【禁止事项】
            - 禁止输出完整可提交的 AC 题解代码（讲解模式最多给关键片段 + 填空题）。
            - 禁止不解释就直接报答案。
            - 禁止评价学生人格，只评价代码与思路。
            - 禁止直接照搬【参考资料】中的原文或完整代码。

            【当前题目】%s
            【题目描述】%s
            【学生当前代码】%s
            【参考资料】%s
            """;

    private final AiGateway aiGateway;
    private final PromptRegistry promptRegistry;
    private final AiChatSessionService sessionService;
    private final RagContextService ragContextService;
    private final AiQuotaService quotaService;
    private final AiContestGuard contestGuard;
    private final TeacherWorkbenchService teacherWorkbenchService;
    private final ReviewApplicationService reviewApplicationService;

    public AiService(AiGateway aiGateway,
                     PromptRegistry promptRegistry,
                     AiChatSessionService sessionService,
                     RagContextService ragContextService,
                     AiQuotaService quotaService,
                     AiContestGuard contestGuard,
                     TeacherWorkbenchService teacherWorkbenchService,
                     ReviewApplicationService reviewApplicationService) {
        this.aiGateway = aiGateway;
        this.promptRegistry = promptRegistry;
        this.sessionService = sessionService;
        this.ragContextService = ragContextService;
        this.quotaService = quotaService;
        this.contestGuard = contestGuard;
        this.teacherWorkbenchService = teacherWorkbenchService;
        this.reviewApplicationService = reviewApplicationService;
        // 版本化注册：后续改提示词只新增版本号，不影响历史链路
        // （教师提示词见 TeacherWorkbenchService，复盘提示词见 ReviewApplicationService）
        promptRegistry.register("tutor", 1, TUTOR_SYSTEM_PROMPT);
    }

    /**
     * 教师侧 AI 工具（A4）：命题草稿 / 测试数据 / 学情解读。
     * 非流式，失败返回降级文案；不写对话历史。
     * <p>
     * 契约实现：{@link AiAssistant#chatWithTool}。
     */
    @Override
    public String chatWithTool(String toolKey, String userPrompt) {
        return teacherWorkbenchService.teacherChat(toolKey, userPrompt);
    }

    /**
     * 命题工作台（B）：结构化 JSON 生成（题面 / std / 测试数据 / IP 改名抽取）。
     * 使用 jsonMode 输出，失败返回 null，由调用方决定降级文案；不写对话历史。
     * <p>
     * 契约实现：{@link AiAssistant#chatJsonWithTool}。
     */
    @Override
    public String chatJsonWithTool(String toolKey, String userPrompt) {
        return teacherWorkbenchService.teacherWorkbenchJson(toolKey, userPrompt);
    }

    /** 从模型输出中稳健提取 JSON 对象：容忍 ```json 围栏与前后说明文字；失败返回 null。 */
    public static JSONObject extractJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim();
        if (t.contains("```")) {
            t = t.replaceAll("```[a-zA-Z0-9]*", "").replace("```", "").trim();
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return new JSONObject(t.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        JSONArray messages = new JSONArray();
        messages.add(systemMessage(systemPrompt));
        messages.add(userMessage(userPrompt));
        String answer = aiGateway.complete(messages, false);
        return answer != null ? answer : API_FAIL_FALLBACK;
    }

    @Override
    public String chatJson(String systemPrompt, String userPrompt) {
        JSONArray messages = new JSONArray();
        messages.add(systemMessage(systemPrompt));
        messages.add(userMessage(userPrompt));
        // 失败返回 null，由调用方自行处理；不返回降级文案，避免把失败伪装成 JSON 内容
        return aiGateway.complete(messages, true);
    }

    /**
     * 流式答疑（前端唯一入口）：system(V2+快照+检索资料) → 历史 → 最新提问。
     * 成功后将本轮写入历史；失败时回调一次降级文案且不写历史（不污染上下文）。
     */
    public String streamChatWithHistory(Long userId, Long problemId,
                                        String problemTitle, String problemContent,
                                        String studentCode, String studentQuestion,
                                        Consumer<String> onChunk) {

        JSONArray messages = buildConversationMessages(userId, problemId, problemTitle,
                problemContent, studentCode, studentQuestion, 0);

        DeepSeekClient.StreamCallResult result = aiGateway.stream(messages, onChunk);
        String answer = result != null ? result.content() : null;
        if (answer == null) {
            onChunk.accept(API_FAIL_FALLBACK);
            return null;
        }
        sessionService.appendTurn(userId, problemId, studentQuestion, answer);
        return answer;
    }

    // ---- 防护栏文案 ----

    /** 比赛拦截文案：诚实说明原因，并给出合规的替代路径 */
    static final String CONTEST_BLOCKED_NOTICE =
            "这道题正在进行中的比赛里哦。为了公平起见，比赛期间教练不能讨论这道题。"
                    + "先把会做的部分写下来，比赛结束后随时来找我复盘，我陪你把每个失分点都搞懂！";

    /** 配额耗尽文案：说明规则 + 明日重置，保持鼓励语气 */
    static final String QUOTA_EXCEEDED_NOTICE =
            "今天的提问额度用完啦。教练每天能给每位同学的指导是有限的。"
                    + "先把已有的提示消化吸收、自己动手改改代码，明天额度刷新后我随时都在！";

    /**
     * 带防护栏的流式答疑（Controller 专用入口）：
     * 比赛拦截 → 配额检查 → 模型调用 → 历史写入，全程产出结构化 {@link AiAskOutcome} 供用量记账。
     *
     * @param privileged 豁免角色标记（教师/管理员）。必须在请求线程用 SecurityUtils 判定后传入——
     *                   本方法运行在异步线程池中，SecurityContext 不会传播到这里。
     */
    public AiAskOutcome streamChatGuarded(Long userId, Long problemId,
                                          String problemTitle, String problemContent,
                                          String studentCode, String studentQuestion,
                                          int hintLevel,
                                          boolean privileged,
                                          Consumer<String> onChunk) {

        // 1) 比赛防护：题目处于进行中比赛时拦截（被拦截不消耗配额、不写历史）
        if (contestGuard.shouldBlock(problemId, privileged)) {
            onChunk.accept(CONTEST_BLOCKED_NOTICE);
            return AiAskOutcome.blocked(AiUsage.STATUS_CONTEST_BLOCKED, CONTEST_BLOCKED_NOTICE);
        }

        // 2) 配额检查：今日额度已用完时拒绝（拒绝不消耗模型调用）
        if (!quotaService.tryAcquire(userId, privileged)) {
            onChunk.accept(QUOTA_EXCEEDED_NOTICE);
            return AiAskOutcome.blocked(AiUsage.STATUS_QUOTA_REJECTED, QUOTA_EXCEEDED_NOTICE);
        }

        // 3) 正常调用模型（Hint Ladder：请求级别受已解锁级别约束，服务端强制逐级）
        int unlocked = sessionService.getHintLevel(userId, problemId);
        int effectiveLevel = effectiveHintLevel(hintLevel, unlocked);
        JSONArray messages = buildConversationMessages(userId, problemId, problemTitle,
                problemContent, studentCode, studentQuestion, effectiveLevel);
        String promptText = flattenForEstimate(messages);

        DeepSeekClient.StreamCallResult result = aiGateway.stream(messages, onChunk);
        if (result == null || result.content() == null) {
            onChunk.accept(API_FAIL_FALLBACK);
            return AiAskOutcome.fail(promptText);
        }

        sessionService.appendTurn(userId, problemId, studentQuestion, result.content());
        if (effectiveLevel >= HintLadderGuide.MIN_LEVEL) {
            sessionService.unlockNextHintLevel(userId, problemId, effectiveLevel);
        }
        return AiAskOutcome.success(result.content(), result.usage(), promptText);
    }

    /**
     * AI 代码复盘（A2）：提交失败后按提交 ID 复盘。
     * 实现已下沉 {@link ReviewApplicationService}（独立链路：比赛拦截 → 配额 → SSE → 用量记账）。
     */
    public AiAskOutcome reviewSubmissionStream(Long userId, Long submissionId,
                                               boolean privileged,
                                               Consumer<String> onChunk) {
        return reviewApplicationService.reviewSubmissionStream(userId, submissionId, privileged, onChunk);
    }

    /** 请求级别裁剪：0=默认引导；超出已解锁级别则降级到已解锁级别 */
    private static int effectiveHintLevel(int requested, int unlocked) {
        if (requested <= 0) {
            return 0;
        }
        if (requested > unlocked) {
            log.warn("【AI阶梯】请求 L{} 但仅解锁到 L{}，已降级", requested, unlocked);
            return Math.min(unlocked, HintLadderGuide.MAX_LEVEL);
        }
        return requested;
    }

    /** 拼平消息文本用于 token 估算（仅在 API 未返回 usage 时使用） */
    private static String flattenForEstimate(JSONArray messages) {
        StringBuilder sb = new StringBuilder();
        for (Object item : messages) {
            if (item instanceof JSONObject msg) {
                String content = msg.getStr("content");
                if (content != null) {
                    sb.append(content);
                }
            }
        }
        return sb.toString();
    }

    /** 字符数 → token 粗估：中英混合语料约 2 字符/token（偏保守，宁多勿少） */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 2);
    }

    private JSONArray buildConversationMessages(Long userId, Long problemId,
                                                String problemTitle, String problemContent,
                                                String studentCode, String studentQuestion,
                                                int hintLevel) {
        JSONArray messages = new JSONArray();
        messages.add(systemMessage(buildTutorSystemPrompt(
                problemTitle, problemContent, studentCode,
                ragContextService.buildReference(problemId), hintLevel)));
        sessionService.loadHistory(userId, problemId).forEach(messages::add);
        messages.add(userMessage(studentQuestion));
        return messages;
    }

    private String buildTutorSystemPrompt(String problemTitle, String problemContent,
                                          String studentCode, String reference, int hintLevel) {
        String code = (studentCode != null && !studentCode.trim().isEmpty())
                ? studentCode : "学生还未编写代码";
        String base = promptRegistry.render("tutor",
                problemTitle == null ? "" : problemTitle,
                problemContent == null ? "" : problemContent,
                code,
                reference == null ? "暂无" : reference);
        String guide = HintLadderGuide.guideFor(hintLevel);
        return (base == null ? "" : base) + guide;
    }

    private static JSONObject systemMessage(String content) {
        JSONObject msg = new JSONObject();
        msg.set("role", "system");
        msg.set("content", content);
        return msg;
    }

    private static JSONObject userMessage(String content) {
        JSONObject msg = new JSONObject();
        msg.set("role", "user");
        msg.set("content", content);
        return msg;
    }
}
