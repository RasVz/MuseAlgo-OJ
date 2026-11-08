package com.musealgo.oj.ai.dto;

/** 非流式调用结果；失败时 content 为 null，来源仍保留用于用量与排障。 */
public record AiCompletionResult(
        String content,
        AiTokenUsage usage,
        AiCallSource source,
        String errorType,
        String errorMessage) {

    public static AiCompletionResult success(String content, AiTokenUsage usage, AiCallSource source) {
        return new AiCompletionResult(content, usage, source, null, null);
    }

    public static AiCompletionResult failure(AiCallSource source, String errorType, String errorMessage) {
        return new AiCompletionResult(null, null, source, errorType, errorMessage);
    }

    public boolean success() {
        return content != null;
    }
}
