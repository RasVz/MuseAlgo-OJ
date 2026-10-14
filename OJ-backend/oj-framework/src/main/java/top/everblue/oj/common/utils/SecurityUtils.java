package top.everblue.oj.common.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import top.everblue.oj.common.exception.ForbiddenException;
import top.everblue.oj.common.security.AuthenticatedUser;

/**
 * 安全上下文工具（基座层，oj-framework 提供）。
 * <p>
 * 通过 {@link AuthenticatedUser} 标记接口读取登录主体 ID，
 * 不依赖任何业务模块实体。登录主体实体（oj-system 的 LoginUser）实现该接口即可。
 * </p>
 */
public class SecurityUtils {

    private static final Logger log = LoggerFactory.getLogger(SecurityUtils.class);

    /**
     * 获取当前登录用户 ID
     * @return userId 或 null (如果未登录)
     */
    public static Long getUserId() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null) {
                return null;
            }

            Object principal = authentication.getPrincipal();

            // 情况 A: 登录主体实现了 AuthenticatedUser（LoginUser）
            if (principal instanceof AuthenticatedUser authenticatedUser) {
                return authenticatedUser.getId();
            }

            // 情况 B: 匿名用户（Spring Security 默认 "anonymousUser" 字符串）或未知类型
            if (principal instanceof String) {
                return null; // 未登录，静默返回 null
            }

            log.warn("【严重警告】当前 Principal 类型是: {}，无法获取 userId！请检查 UserDetailsServiceImpl。",
                    principal.getClass().getName());
            return null;

        } catch (Exception e) {
            log.error("获取用户ID失败", e);
            return null;
        }
    }

    public static boolean hasRole(byte role) {
        return switch (role) {
            case 2 -> hasAuthority("ROLE_TEACHER");
            case 3 -> hasAuthority("ROLE_ADMIN");
            default -> hasAuthority("ROLE_STUDENT");
        };
    }

    public static boolean hasAuthority(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authority == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    public static boolean isAdmin() {
        return hasRole((byte) 3);
    }

    public static boolean isTeacherOrAdmin() {
        return hasRole((byte) 2) || isAdmin();
    }

    public static void requireTeacherOrAdmin() {
        if (!isTeacherOrAdmin()) {
            throw new ForbiddenException("需要教师或管理员权限");
        }
    }

    public static void requireOwnerOrAdmin(Long ownerId) {
        Long currentUserId = getUserId();
        if (currentUserId == null || (!isAdmin() && !currentUserId.equals(ownerId))) {
            throw new ForbiddenException("无权操作该资源");
        }
    }
}
