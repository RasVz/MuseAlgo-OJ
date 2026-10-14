package top.everblue.oj.common.security;

/**
 * 已认证用户标记接口 — 由登录主体实体实现（如 oj-system 的 LoginUser）。
 * <p>
 * 让基础层的 {@code SecurityUtils} 能安全取到用户 ID，而不必依赖具体业务模块的实体。
 * </p>
 */
public interface AuthenticatedUser {

    /** 当前登录用户 ID */
    Long getId();
}
