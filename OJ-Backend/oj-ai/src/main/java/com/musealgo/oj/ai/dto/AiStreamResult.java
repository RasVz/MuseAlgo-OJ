package com.musealgo.oj.ai.dto;

/**
 * 流式调用结果。
 *
 * <p>{@code success=false} 时可能已经有部分内容推给客户端；调用方不得把部分内容写入历史，
 * 也不得在已经输出内容后跨服务重试。</p>
 */
public record AiStreamResult(
        String content,
        AiTokenUsage usage,
        AiCallSource source,
        boolean success,
        boolean emitted,
        String errorType,
        String errorMessage) {

    public static AiStreamResult success(String content, AiTokenUsage usage, AiCallSource source, boolean emitted) {
        return new AiStreamResult(content, usage, source, true, emitted, null, null);
    }

    public static AiStreamResult failure(AiCallSource source, boolean emitted, String errorType, String errorMessage) {
        return new AiStreamResult(null, null, source, false, emitted, errorType, errorMessage);
    }
}
