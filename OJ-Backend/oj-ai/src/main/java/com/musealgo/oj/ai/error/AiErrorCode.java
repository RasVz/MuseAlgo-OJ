package com.musealgo.oj.ai.error;

import com.musealgo.oj.common.exception.ErrorCode;

/** Stable public reasons owned by this module. */
public enum AiErrorCode implements ErrorCode {
    AI_QUOTA_EXCEEDED(429, "今日 AI 使用额度已用完"),
    AI_CONTEST_BLOCKED(403, "比赛进行中，暂不能使用 AI 助手"),
    AI_UPSTREAM_FAILED(503, "AI 服务暂时不可用，请稍后重试"),
    AI_RESPONSE_INVALID(502, "AI 返回内容格式不正确，请重试"),
    AI_STREAM_BUSY(503, "AI 请求较多，请稍后重试"),
    AI_STREAM_FAILED(500, "AI 回复中断，请重试"),
    AI_REVIEW_SUBMISSION_NOT_FOUND(404, "提交不存在"),
    AI_INPUT_INVALID(400, "请求参数不合法"),
    AI_PROMPT_NOT_FOUND(404, "模板不存在: "),
    AI_STATE_CONFLICT(409, "当前状态不允许该操作");

    private final int status;
    private final String message;
    AiErrorCode(int status, String message) { this.status = status; this.message = message; }
    public String key() { return name(); }
    public int status() { return status; }
    public String message() { return message; }
}
