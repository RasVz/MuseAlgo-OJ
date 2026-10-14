package top.everblue.oj.ai;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.everblue.oj.ai.dto.AiAskOutcome;
import top.everblue.oj.ai.entity.AiUsage;
import top.everblue.oj.ai.gateway.AiGateway;
import top.everblue.oj.ai.gateway.PromptRegistry;
import top.everblue.oj.ai.guard.AiContestGuard;
import top.everblue.oj.ai.quota.AiQuotaService;
import top.everblue.oj.ai.DeepSeekClient;
import top.everblue.oj.api.submission.SubmissionReviewQuery;
import top.everblue.oj.api.submission.SubmissionReviewQuery.SubmissionReviewData;

import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * AI 代码复盘链路（A2）：按提交 ID 复盘，独立于学生答疑（不写历史、不参与 Hint Ladder）。
 * <p>
 * 2026-08-15 大服务类拆分：从 {@link AiService} 拆出。
 * 链路与答疑一致：比赛拦截 → 配额 → 模型调用（SSE）→ 用量记账。
 * </p>
 */
@Slf4j
@Service
public class ReviewApplicationService {

    private static final String API_FAIL_FALLBACK = "糟糕，教练的大脑暂时短路了，请稍后再试一下吧！";
    private static final String REVIEW_FORBIDDEN_NOTICE = "无权复盘其他用户的提交。";

    /**
     * AI 代码复盘（A2）：只接收「代码 + 评测特征」，不接收测试点原文。
     * 评测特征（状态/耗时/内存/错误/用例状态计数）由调用方从 DB 只读契约取。
     */
    private static final String REVIEW_SYSTEM_PROMPT = """
            你是资深信奥教练。学生提交的代码评测失败了，请做一次复盘，帮助他定位问题并自己修好。

            【复盘要求】
            1. 先读代码，判断最可能的错误原因：WA 重点看边界条件/类型溢出/算法思路，TLE 重点看复杂度与死循环，RE 重点看越界/空指针/除零，CE 直接说编译错误原因。
            2. 指出出错位置（函数/行号附近），说明为什么错。
            3. 给 1~3 条可执行的修改建议；可以给关键代码片段，但禁止直接贴完整可提交的 AC 题解。
            4. 结尾问 1 个引导问题，让学生自己动手改。

            【输出格式】正文不超过 400 字；代码用 ```cpp 围栏；关键行带注释。
            【禁止事项】不评价学生人格；不直接照搬题解；不提及测试点原文内容。
            """;

    /** 比赛拦截文案：诚实说明原因，并给出合规的替代路径 */
    private static final String CONTEST_BLOCKED_NOTICE =
            "这道题正在进行中的比赛里哦。为了公平起见，比赛期间教练不能讨论这道题。"
                    + "先把会做的部分写下来，比赛结束后随时来找我复盘，我陪你把每个失分点都搞懂！";

    /** 配额耗尽文案：说明规则 + 明日重置，保持鼓励语气 */
    private static final String QUOTA_EXCEEDED_NOTICE =
            "今天的提问额度用完啦。教练每天能给每位同学的指导是有限的。"
                    + "先把已有的提示消化吸收、自己动手改改代码，明天额度刷新后我随时都在！";

    private final AiGateway aiGateway;
    private final PromptRegistry promptRegistry;
    private final AiQuotaService quotaService;
    private final AiContestGuard contestGuard;
    private final SubmissionReviewQuery submissionReviewQuery;

    public ReviewApplicationService(AiGateway aiGateway,
                                    PromptRegistry promptRegistry,
                                    AiQuotaService quotaService,
                                    AiContestGuard contestGuard,
                                    SubmissionReviewQuery submissionReviewQuery) {
        this.aiGateway = aiGateway;
        this.promptRegistry = promptRegistry;
        this.quotaService = quotaService;
        this.contestGuard = contestGuard;
        this.submissionReviewQuery = submissionReviewQuery;
        promptRegistry.register("review", 1, REVIEW_SYSTEM_PROMPT);
    }

    /**
     * AI 代码复盘（A2）：提交失败后按提交 ID 复盘。
     * 链路与答疑一致：比赛拦截 → 配额 → 模型调用（SSE）→ 用量记账；
     * 不写对话历史、不参与 Hint Ladder 解锁。
     */
    public AiAskOutcome reviewSubmissionStream(Long userId, Long submissionId,
                                               boolean privileged,
                                               Consumer<String> onChunk) {
        SubmissionReviewData data = submissionReviewQuery.findById(submissionId).orElse(null);
        if (data == null || data.code() == null || data.code().isBlank()) {
            onChunk.accept(API_FAIL_FALLBACK);
            return AiAskOutcome.fail("");
        }

        if (userId == null || !userId.equals(data.userId())) {
            onChunk.accept(REVIEW_FORBIDDEN_NOTICE);
            return AiAskOutcome.fail("");
        }

        if (contestGuard.shouldBlock(data.problemId(), privileged)) {
            onChunk.accept(CONTEST_BLOCKED_NOTICE);
            return AiAskOutcome.blocked(AiUsage.STATUS_CONTEST_BLOCKED, CONTEST_BLOCKED_NOTICE);
        }

        if (!quotaService.tryAcquire(userId, privileged)) {
            onChunk.accept(QUOTA_EXCEEDED_NOTICE);
            return AiAskOutcome.blocked(AiUsage.STATUS_QUOTA_REJECTED, QUOTA_EXCEEDED_NOTICE);
        }

        JSONArray messages = new JSONArray();
        messages.add(systemMessage(promptRegistry.render("review")));
        messages.add(userMessage(buildReviewUserPrompt(data)));
        String promptText = flattenForEstimate(messages);

        DeepSeekClient.StreamCallResult result = aiGateway.stream(messages, onChunk);
        if (result == null || result.content() == null) {
            onChunk.accept(API_FAIL_FALLBACK);
            return AiAskOutcome.fail(promptText);
        }
        return AiAskOutcome.success(result.content(), result.usage(), promptText);
    }

    /** 组装复盘 user 消息：代码 + 评测特征（不含测试点原文） */
    private static String buildReviewUserPrompt(SubmissionReviewData data) {
        StringBuilder sb = new StringBuilder();
        sb.append("【评测结果】\n");
        sb.append("状态: ").append(statusLabel(data.status())).append('\n');
        sb.append("得分: ").append(data.score()).append('\n');
        sb.append("耗时: ").append(data.runTime()).append("ms，内存: ")
                .append(data.runMemory()).append("kb\n");
        if (data.errorMessage() != null && !data.errorMessage().isBlank()) {
            sb.append("错误信息: ").append(data.errorMessage()).append('\n');
        }
        if (data.caseStats() != null && !data.caseStats().isEmpty()) {
            sb.append("用例统计: ");
            sb.append(data.caseStats().stream()
                    .map(c -> statusLabel((byte) c.status()) + "×" + c.count())
                    .collect(Collectors.joining(", ")));
            sb.append('\n');
        }
        sb.append("\n【学生代码】\n```").append(data.language() == null ? "cpp" : data.language())
                .append("\n").append(data.code()).append("\n```");
        return sb.toString();
    }

    private static String statusLabel(Byte status) {
        if (status == null) return "UNKNOWN";
        return switch (status) {
            case 1 -> "AC";
            case 2 -> "WA";
            case 3 -> "CE";
            case 4 -> "RE";
            case 5 -> "TLE";
            default -> "PENDING";
        };
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
