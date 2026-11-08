package com.musealgo.oj.judge.error;

import com.musealgo.oj.common.exception.ErrorCode;

/** Stable public reasons owned by this module. */
public enum JudgeErrorCode implements ErrorCode {
    REJUDGE_CONTEST_BLOCKED(409, "当前比赛状态不允许重判"),
    JUDGE_INPUT_INVALID(400, "请求参数不合法"),
    LANGUAGE_UNSUPPORTED(400, "当前仅支持 C++ / Java / Python 提交"),
    REJUDGE_NO_ELIGIBLE_SUBMISSIONS(409, "没有可重判的提交："),
    REJUDGE_NO_TEST_CASES(409, "题目没有可用测试用例，无法重判"),
    REJUDGE_OPERATION_NOT_FOUND(404, "重判操作不存在"),
    REJUDGE_SCOPE_TOO_LARGE(400, "匹配提交超过扫描上限 "),
    REJUDGE_SUBMISSION_PENDING(409, "提交正在判题中，不能重判"),
    REJUDGE_VP_ACTIVE(409, "VP 会话尚未结束（RUNNING/SETTLING），不能重判"),
    SUBMISSION_CODE_REQUIRED(400, "代码不能为空"),
    SUBMISSION_NOT_FOUND(404, "提交不存在"),
    SUBMISSION_NO_TEST_CASES(409, "题目没有可用测试用例，无法评测");

    private final int status;
    private final String message;
    JudgeErrorCode(int status, String message) { this.status = status; this.message = message; }
    public String key() { return name(); }
    public int status() { return status; }
    public String message() { return message; }
}
