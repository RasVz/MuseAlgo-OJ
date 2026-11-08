package com.musealgo.oj.common.exception;

/** Shared reasons; domain-specific definitions live in their owning modules. */
public enum CommonErrorCode implements ErrorCode {
    INVALID_ARGUMENT(400, "请求参数不合法"),
    REQUEST_BODY_INVALID(400, "请求内容格式不正确"),
    REQUIRED_PARAMETER_MISSING(400, "缺少必要的请求参数"),
    PARAMETER_TYPE_MISMATCH(400, "请求参数类型不正确"),
    VALIDATION_FAILED(400, "请求参数校验失败"),
    AUTH_REQUIRED(401, "请先登录"),
    AUTH_INVALID_CREDENTIALS(401, "用户名或密码错误"),
    AUTH_TOKEN_EXPIRED(401, "登录凭据已过期"),
    AUTH_TOKEN_INVALID(401, "登录凭据已失效，请重新登录"),
    AUTH_TOKEN_REVOKED(401, "登录已注销，请重新登录"),
    AUTH_REFRESH_INVALID(401, "登录凭据已失效，请重新登录"),
    ACCESS_DENIED(403, "无权访问该资源"),
    RESOURCE_NOT_FOUND(404, "请求的资源不存在"),
    ROUTE_NOT_FOUND(404, "请求的接口不存在"),
    METHOD_NOT_ALLOWED(405, "不支持当前请求方法"),
    RESPONSE_MEDIA_TYPE_UNSUPPORTED(406, "不支持请求的响应格式"),
    STATE_CONFLICT(409, "当前状态不允许该操作"),
    UPLOAD_TOO_LARGE(413, "上传文件超过大小限制"),
    MEDIA_TYPE_UNSUPPORTED(415, "不支持当前请求内容格式"),
    AUTH_LOGIN_COOLDOWN(429, "账号或密码错误次数过多，请稍后重试"),
    RATE_LIMITED(429, "请求过于频繁，请稍后重试"),
    INTERNAL_ERROR(500, "系统暂时不可用，请稍后重试"),
    DEPENDENCY_UNAVAILABLE(503, "服务暂时不可用，请稍后重试"),
    AUTH_DEPENDENCY_UNAVAILABLE(503, "暂时无法验证登录状态，请稍后重试"),
    UPSTREAM_TIMEOUT(504, "服务响应超时，请稍后重试");

    private final int status;
    private final String message;
    CommonErrorCode(int status, String message) { this.status = status; this.message = message; }
    public String key() { return name(); }
    public int status() { return status; }
    public String message() { return message; }
}
