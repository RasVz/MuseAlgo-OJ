package com.musealgo.oj.ai.dto;

/** 供应商返回的 token 用量；字段缺失时全部为 0。 */
public record AiTokenUsage(int promptTokens, int completionTokens, int totalTokens) {

    public static AiTokenUsage empty() {
        return new AiTokenUsage(0, 0, 0);
    }
}
