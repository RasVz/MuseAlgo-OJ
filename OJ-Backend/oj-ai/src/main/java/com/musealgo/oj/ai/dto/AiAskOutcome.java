package com.musealgo.oj.ai.dto;

/** Stream outcome and accounting context; problemId is resolved by the application service. */
public record AiAskOutcome(String status, String answer, AiTokenUsage usage,
                           String promptText, AiCallSource source, String errorType, Long problemId, boolean requestSent) {
    public AiAskOutcome(String status, String answer, AiTokenUsage usage, String promptText,
                        AiCallSource source, String errorType, Long problemId) {
        this(status, answer, usage, promptText, source, errorType, problemId, "SUCCESS".equals(status) || usage != null);
    }
    public AiAskOutcome(String status, String answer, AiTokenUsage usage, String promptText, AiCallSource source) {
        this(status, answer, usage, promptText, source, null, null, "SUCCESS".equals(status) || usage != null);
    }
    public AiAskOutcome(String status, String answer, AiTokenUsage usage, String promptText, AiCallSource source, String errorType) {
        this(status, answer, usage, promptText, source, errorType, null, "SUCCESS".equals(status) || usage != null);
    }
    public AiAskOutcome withProblemId(Long id) {
        return new AiAskOutcome(status, answer, usage, promptText, source, errorType, id, requestSent);
    }
    public static AiAskOutcome success(String answer, AiTokenUsage usage, String promptText) {
        return success(answer, usage, promptText, null);
    }
    public static AiAskOutcome success(String answer, AiTokenUsage usage, String promptText, AiCallSource source) {
        return new AiAskOutcome("SUCCESS", answer, usage, promptText, source);
    }
    public static AiAskOutcome fail(String promptText) { return fail(promptText, null); }
    public static AiAskOutcome fail(String promptText, AiCallSource source) { return fail(promptText, source, null); }
    public static AiAskOutcome fail(String promptText, AiCallSource source, String errorType) {
        return new AiAskOutcome("FAIL", null, null, promptText, source, errorType);
    }
    public static AiAskOutcome blocked(String status, String notice) {
        return new AiAskOutcome(status, notice, null, "", null);
    }
}
