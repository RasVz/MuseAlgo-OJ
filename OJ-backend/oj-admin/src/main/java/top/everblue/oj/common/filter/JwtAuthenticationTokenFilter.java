package top.everblue.oj.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import top.everblue.oj.common.utils.JwtUtils;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.system.service.JwtBlacklistService;
import top.everblue.oj.system.service.impl.UserDetailsServiceImpl;

import java.io.IOException;

/**
 * JWT 鉴权过滤器：每个请求解析 Authorization 头，把用户身份写入 SecurityContext。
 * <p>
 * 定位是「无状态鉴权的执行者」而非「拦截器」——token 无效时它<strong>不返回 401，而是当作未登录放行</strong>，
 * 由 {@link SecurityConfig} 的 {@code anyRequest().authenticated()} 决定这条请求最终是否放行。
 * 这样职责才清晰：本类只回答「你是谁」，不回答「准不准进」。
 * </p>
 * <p>
 * 身份加载刻意走 {@code loadUserByUsername} 而不是直接从 token 里取 userId：token 是签发时的快照，
 * 用户被封禁、改角色后，旧 token 里的角色信息仍然有效。每次请求回查数据库虽然多一次查询，
 * 但保证权限变更立刻生效——这是「改完权限要重新登录」这类体验问题的取舍，优先选安全侧。
 * </p>
 * <p>
 * 注销黑名单的判定嵌在校验链里：签名有效但 jti 已注销的 token 一律按未认证处理。
 * 其中「无 jti 的存量 token 跳过检查」是为兼容注销功能上线前签发的老 token，
 * 这些 token 最多再活几小时就会自然过期，属于可控窗口，不要为了「逻辑干净」改成直接拒绝。
 * </p>
 * <p>
 * {@code finally} 里清理 SecurityContext 与 MDC 是必须的：容器线程池会复用线程，
 * 不清理会把上一个请求的身份带给下一个请求（串号），MDC 不清理则日志里的 userId 会串行错位。
 * </p>
 */
@Component
public class JwtAuthenticationTokenFilter extends OncePerRequestFilter {

    /** MDC 键：当前登录用户 ID（未认证请求不写入，JSON 日志中该字段留空） */
    private static final String MDC_USER_ID_KEY = "userId";

    @Autowired
    private UserDetailsServiceImpl userDetailsService;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private JwtBlacklistService jwtBlacklistService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        // 1. 鉴权逻辑（解析 Token 并存入官方保险箱）
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                String username = jwtUtils.extractUsername(token);

                if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                    // 黑名单检查：已注销的 token（jti 命中）视为未认证；无 jti 的存量 token 跳过（兼容）
                    if (jwtUtils.validateAccessToken(token, userDetails.getUsername())
                            && !jwtBlacklistService.isRevoked(jwtUtils.extractJti(token))) {
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                        // 【核心】设置 Security 上下文。只要有了这一句，后面的 SecurityUtils 就能自动拿到 ID
                        SecurityContextHolder.getContext().setAuthentication(authentication);

                        // MDC：认证通过后写入 userId，供 JSON 日志按用户关联（未认证请求不写入）
                        Long userId = SecurityUtils.getUserId();
                        if (userId != null) {
                            MDC.put(MDC_USER_ID_KEY, userId.toString());
                        }
                    }
                }
            } catch (Exception e) {
                // Token 过期/无效是正常场景（用户需重新登录），用 warn 级别即可
                logger.warn("JWT 校验失败: " + e.getMessage());
            }
        }

        // 2. 放行逻辑（用 try-finally 包裹）
        try {
            chain.doFilter(request, response);
        } finally {
            // 3. 清理逻辑（请求处理完后，清空保险箱，防止内存泄漏和串号）
            SecurityContextHolder.clearContext();
            MDC.remove(MDC_USER_ID_KEY);
        }
    }
}