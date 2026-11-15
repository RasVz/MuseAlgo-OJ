package com.musealgo.oj.ai.error;

import com.musealgo.oj.common.exception.ErrorCode;

/** Stable public reasons owned by this module. */
public enum AiErrorCode implements ErrorCode {
    AI_QUOTA_EXCEEDED(429, "今日 AI 使用额度已用完"),
    AI_CONTEST_BLOCKED(403, "比赛场景不提供 AI 教学帮助，请到普通练习中使用"),
    AI_UPSTREAM_FAILED(503, "AI 服务暂时不可用，请稍后重试"),
    AI_RESPONSE_INVALID(502, "AI 返回内容格式不正确，请重试"),
    AI_STREAM_BUSY(503, "AI 请求较多，请稍后重试"),
    AI_RESPONSE_FILTERED(502, "供应商未能完成本次回复，请调整问题后重试"),
    AI_OUTPUT_LIMIT(502, "回复达到输出上限，尚未完成，请缩小问题范围或调整输出上限"),
    AI_STREAM_FAILED(500, "AI 回复中断，请重试"),
    AI_DIAGNOSIS_SUBMISSION_NOT_FOUND(404, "提交不存在"),
    AI_DIAGNOSIS_EXAM_BLOCKED(403, "试卷提交暂不支持 AI 诊断，请在普通练习中使用"),
    AI_DIAGNOSIS_ALREADY_EXISTS(409, "该提交已生成诊断"),
    AI_DIAGNOSIS_IN_PROGRESS(409, "该提交的诊断正在生成，请稍后刷新"),
    AI_DIAGNOSIS_INTERRUPTED(409, "生成已中断，请重试"),
    AI_INPUT_INVALID(400, "请求参数不合法"),
    AI_PROMPT_NOT_FOUND(404, "模板不存在: "),
    AI_DRAFT_NOT_FOUND(404, "草稿不存在或已删除"),
    AI_STATE_CONFLICT(409, "当前状态不允许该操作");

    private final int status;
    private final String message;
    AiErrorCode(int status, String message) { this.status = status; this.message = message; }
    public String key() { return name(); }
    public int status() { return status; }
    public String message() { return message; }
}
