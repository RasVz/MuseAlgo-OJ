package com.musealgo.oj.teaching.error;

import com.musealgo.oj.common.exception.ErrorCode;

/** Stable public reasons owned by this module. */
public enum TeachingErrorCode implements ErrorCode {
    CLASS_ALREADY_JOINED(409, "您已在班级中，无需重复加入"),
    CLASS_COURSEWARE_ALREADY_ADDED(409, "该课件已添加到班级"),
    CLASS_COURSEWARE_NOT_FOUND(404, "班级课件不存在"),
    CLASS_INVITE_INVALID(400, "邀请码无效或已过期"),
    CLASS_MEMBER_NOT_FOUND(404, "成员不存在"),
    CLASS_NOT_FOUND(404, "班级不存在"),
    COURSEWARE_BUILD_BUSY(503, "课件构建繁忙，请稍后重试"),
    COURSEWARE_BUILD_INVALID(400, "Slidev编译失败，请检查课件内容"),
    COURSEWARE_BUILD_TIMEOUT(504, "课件构建超时"),
    COURSEWARE_NOT_FOUND(404, "课件不存在"),
    PROBLEM_LIST_NOT_FOUND(404, "题单不存在"),
    TEACHING_INPUT_INVALID(400, "请求参数不合法");

    private final int status;
    private final String message;
    TeachingErrorCode(int status, String message) { this.status = status; this.message = message; }
    public String key() { return name(); }
    public int status() { return status; }
    public String message() { return message; }
}
