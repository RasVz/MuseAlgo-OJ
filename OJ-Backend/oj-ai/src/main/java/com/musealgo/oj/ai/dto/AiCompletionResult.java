package com.musealgo.oj.ai.dto;

/** 非流式结果；缓存命中保留标志，避免把未发生的调用估算为供应商消耗。 */
public record AiCompletionResult(String content, AiTokenUsage usage, AiCallSource source,
        String errorType, String errorMessage, boolean cached) {
    public AiCompletionResult(String content,AiTokenUsage usage,AiCallSource source,String errorType,String errorMessage) {
        this(content,usage,source,errorType,errorMessage,false);
    }
    public static AiCompletionResult success(String content,AiTokenUsage usage,AiCallSource source) { return new AiCompletionResult(content,usage,source,null,null); }
    public static AiCompletionResult failure(AiCallSource source,String errorType,String errorMessage) { return new AiCompletionResult(null,null,source,errorType,errorMessage); }
    public boolean success() { return content != null; }
}
