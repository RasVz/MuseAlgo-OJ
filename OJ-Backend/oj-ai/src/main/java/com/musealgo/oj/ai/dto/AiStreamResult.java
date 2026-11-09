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
        String errorMessage,
        boolean requestSent, java.util.List<ToolCall> toolCalls, boolean usageEstimated) {
    public record ToolCall(String id, String name, String arguments) { }
    public AiStreamResult { toolCalls = toolCalls == null ? java.util.List.of() : java.util.List.copyOf(toolCalls); }
    public AiStreamResult(String content, AiTokenUsage usage, AiCallSource source, boolean success,
                          boolean emitted, String errorType, String errorMessage, boolean requestSent) {
        this(content, usage, source, success, emitted, errorType, errorMessage, requestSent, java.util.List.of(), false);
    }
    public AiStreamResult(String content, AiTokenUsage usage, AiCallSource source, boolean success,
                          boolean emitted, String errorType, String errorMessage) {
        this(content, usage, source, success, emitted, errorType, errorMessage, success || emitted || usage != null);
    }

    public static AiStreamResult success(String content, AiTokenUsage usage, AiCallSource source, boolean emitted) {
        return new AiStreamResult(content, usage, source, true, emitted, null, null);
    }

    public static AiStreamResult failure(AiCallSource source, boolean emitted, String errorType, String errorMessage) {
        return new AiStreamResult(null, null, source, false, emitted, errorType, errorMessage, false);
    }
}
