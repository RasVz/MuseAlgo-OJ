package com.musealgo.oj.problem.error;

import com.musealgo.oj.common.exception.ErrorCode;

/** Stable public reasons owned by this module. */
public enum ProblemErrorCode implements ErrorCode {
    EXAM_ATTEMPT_EXPIRED(409, "作答时间已结束，试卷已自动交卷"),
    PROBLEM_CASE_SET_CHANGED(409, "测试用例已变化，请重新测试"),
    AI_RESPONSE_INVALID(502, "AI 未返回内容"),
    AI_UNAVAILABLE(503, "AI 服务暂不可用，请稍后重试"),
    EXAM_ATTEMPT_NOT_ACTIVE(409, "请先开始作答，或当前试卷已交卷"),
    EXAM_PAPER_NOT_FOUND(404, "试卷不存在: "),
    PROBLEM_CASE_NOT_FOUND(404, "测试用例不存在"),
    PROBLEM_INPUT_INVALID(400, "请求参数不合法"),
    PROBLEM_LIST_ITEM_ALREADY_EXISTS(409, "该题目已在题单中"),
    PROBLEM_LIST_ITEM_NOT_FOUND(404, "该题目不在题单中"),
    PROBLEM_LIST_NOT_FOUND(404, "题单不存在"),
    PROBLEM_NOT_FOUND(404, "题目不存在"),
    /** 写入未生效属于服务端故障，保留 500 语义但给出可定位的业务码。 */
    PROBLEM_WRITE_FAILED(500, "题目写入失败，请稍后重试");

    private final int status;
    private final String message;
    ProblemErrorCode(int status, String message) { this.status = status; this.message = message; }
    public String key() { return name(); }
    public int status() { return status; }
    public String message() { return message; }
}
