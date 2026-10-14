package top.everblue.oj.ai.dto;

import top.everblue.oj.ai.DeepSeekClient.TokenUsage;

/**
 * 一次 AI 答疑请求的结构化结果（Controller 据此记录用量流水）。
 *
 * @param status     SUCCESS / FAIL / QUOTA_REJECTED / CONTEST_BLOCKED
 * @param answer     完整回答文本（被拦截/拒载时为降级文案；失败时为 null）
 * @param usage      API 返回的 token 用量，可能为 null（调用方退化为字符估算）
 * @param promptText 实际发送的提示词快照（估算 token 用；不持久化原文）
 */
public record AiAskOutcome(String status, String answer, TokenUsage usage, String promptText) {

    public static AiAskOutcome success(String answer, TokenUsage usage, String promptText) {
        return new AiAskOutcome("SUCCESS", answer, usage, promptText);
    }

    public static AiAskOutcome fail(String promptText) {
        return new AiAskOutcome("FAIL", null, null, promptText);
    }

    public static AiAskOutcome blocked(String status, String notice) {
        return new AiAskOutcome(status, notice, null, "");
    }
}
