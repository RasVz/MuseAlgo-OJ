package com.musealgo.oj.community.error;

import com.musealgo.oj.common.exception.ErrorCode;

/** Stable public reasons owned by this module. */
public enum CommunityErrorCode implements ErrorCode {
    COMMENT_NOT_FOUND(404, "评论不存在"),
    COMMENT_TARGET_NOT_FOUND(404, "评论目标不存在"),
    COMMUNITY_INPUT_INVALID(400, "请求参数不合法"),
    COMMUNITY_REPORT_ALREADY_PENDING(409, "你已举报过该内容，正在等待审核"),
    COMMUNITY_REPORT_NOT_FOUND(404, "举报记录不存在"),
    COMMUNITY_TAG_ALREADY_EXISTS(409, "该标签已存在"),
    FAVORITE_TARGET_NOT_FOUND(404, "收藏目标不存在"),
    INTERACTION_UNSUPPORTED(400, "该内容不支持此互动"),
    POST_NOT_FOUND(404, "内容不存在"),
    POST_RELATED_PROBLEM_INVALID(400, "包含不存在的关联题目"),
    SELF_FOLLOW_NOT_ALLOWED(409, "不能关注自己");

    private final int status;
    private final String message;
    CommunityErrorCode(int status, String message) { this.status = status; this.message = message; }
    public String key() { return name(); }
    public int status() { return status; }
    public String message() { return message; }
}
