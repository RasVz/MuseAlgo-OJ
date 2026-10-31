package com.musealgo.oj.ai.dto;

import com.musealgo.oj.ai.dto.AiCallSource;
import com.musealgo.oj.ai.dto.AiTokenUsage;

/**
 * 一次 AI 答疑请求的结构化结果（Controller 据此记录用量流水）。
 *
 * @param status     SUCCESS / FAIL / QUOTA_REJECTED / CONTEST_BLOCKED
 * @param answer     完整回答文本（被拦截/拒载时为降级文案；失败时为 null）
 * @param usage      API 返回的 token 用量，可能为 null（调用方退化为字符估算）
 * @param promptText 实际发送的提示词快照（估算 token 用；不持久化原文）
 * @param source     本次请求实际使用的服务/模型/配置版本；未调用模型时可为 null
 */
public record AiAskOutcome(String status, String answer, AiTokenUsage usage,
                           String promptText, AiCallSource source) {

    public static AiAskOutcome success(String answer, AiTokenUsage usage, String promptText) {
        return success(answer, usage, promptText, null);
    }

    public static AiAskOutcome success(String answer, AiTokenUsage usage,
                                       String promptText, AiCallSource source) {
        return new AiAskOutcome("SUCCESS", answer, usage, promptText, source);
    }

    public static AiAskOutcome fail(String promptText) {
        return fail(promptText, null);
    }

    public static AiAskOutcome fail(String promptText, AiCallSource source) {
        return new AiAskOutcome("FAIL", null, null, promptText, source);
    }

    public static AiAskOutcome blocked(String status, String notice) {
        return new AiAskOutcome(status, notice, null, "", null);
    }
}
