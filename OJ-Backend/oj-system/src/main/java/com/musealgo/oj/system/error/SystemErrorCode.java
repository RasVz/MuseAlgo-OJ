package com.musealgo.oj.system.error;

import com.musealgo.oj.common.exception.ErrorCode;

/** Stable public reasons owned by this module. */
public enum SystemErrorCode implements ErrorCode {
    ADMIN_LAST_ADMIN_REQUIRED(409, "系统必须保留至少一个管理员"),
    ADMIN_SELF_BAN_NOT_ALLOWED(409, "不能禁用自己的账号"),
    ADMIN_SELF_DEMOTION_NOT_ALLOWED(409, "不能降低自己的管理员角色"),
    AUTH_CURRENT_PASSWORD_INCORRECT(400, "当前密码错误"),
    AUTH_EMAIL_TAKEN(409, "邮箱已被使用"),
    AUTH_USERNAME_TAKEN(409, "用户名已被使用"),
    REVIEW_ENTRY_NOT_FOUND(404, "错题记录不存在"),
    SYSTEM_INPUT_INVALID(400, "请求参数不合法"),
    USER_NOT_FOUND(404, "用户不存在");

    private final int status;
    private final String message;
    SystemErrorCode(int status, String message) { this.status = status; this.message = message; }
    public String key() { return name(); }
    public int status() { return status; }
    public String message() { return message; }
}
