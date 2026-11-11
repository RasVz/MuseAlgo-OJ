package com.musealgo.oj.common.filter;

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
import com.musealgo.oj.common.utils.JwtUtils;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.system.service.JwtBlacklistService;
import com.musealgo.oj.system.service.impl.UserDetailsServiceImpl;
import com.musealgo.oj.system.entity.User;

import java.io.IOException;
import com.musealgo.oj.common.exception.CommonErrorCode;
import com.musealgo.oj.common.exception.DependencyUnavailableException;
import com.musealgo.oj.common.exception.ErrorResponses;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * JWT 鉴权过滤器：每个请求解析 Authorization 头，把用户身份写入 SecurityContext。
 * <p>
 * 定位是「无状态鉴权的执行者」而非「拦截器」——token 无效时它<strong>不返回 401，而是当作未登录放行</strong>，
 * 由 {@link com.musealgo.oj.common.config.SecurityConfig} 决定这条请求是否需要认证。
 * 依赖故障直接返回安全的不可用响应，保持拒绝授权并保留故障原因。
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
    public static final String AUTH_ERROR_ATTRIBUTE = "oj.authErrorCode";
    private static final String MDC_USER_ID_KEY = "userId";
    private static final Pattern COURSEWARE_OUTPUT_DIRECTORY = Pattern.compile("[0-9a-fA-F]{32}");

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
        String token = null;
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else if (isCoursewareStaticRequest(request)) {
            token = Arrays.stream(request.getCookies() == null ? new jakarta.servlet.http.Cookie[0] : request.getCookies())
                    .filter(cookie -> "courseware_access".equals(cookie.getName()))
                    .map(jakarta.servlet.http.Cookie::getValue)
                    .filter(StringUtils::hasText)
                    .findFirst()
                    .orElse(null);
        }

        try {
            // Invalid credentials may still access public routes; infrastructure failures cannot authorize a caller.
            if (StringUtils.hasText(token)) request.setAttribute(AUTH_ERROR_ATTRIBUTE, CommonErrorCode.AUTH_TOKEN_INVALID);
            // 1. 鉴权逻辑（解析 Token 并存入官方保险箱）
            if (StringUtils.hasText(token)) {
                try {
                    String username = jwtUtils.extractUsername(token);

                    if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                        UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                        // 黑名单检查：已注销的 token（jti 命中）视为未认证；无 jti 的存量 token 跳过（兼容）
                        if (jwtUtils.validateAccessToken(token, userDetails.getUsername())
                                && userDetails instanceof User user
                                && jwtUtils.hasCurrentCredentialVersion(token, user.getCredentialVersion())) {
                            if (user.getIsBanned() != null && user.getIsBanned() == 1) {
                                // 封禁账号的存量 token 一律视为失效；不在会话路径暴露“已封禁”，避免仅凭 token 探测账号状态。
                                request.setAttribute(AUTH_ERROR_ATTRIBUTE, CommonErrorCode.AUTH_TOKEN_INVALID);
                            } else if (jwtBlacklistService.isRevoked(jwtUtils.extractJti(token))) {
                                request.setAttribute(AUTH_ERROR_ATTRIBUTE, CommonErrorCode.AUTH_TOKEN_REVOKED);
                            } else {
                            UsernamePasswordAuthenticationToken authentication =
                                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                            // 【核心】设置 Security 上下文。只要有了这一句，后面的 SecurityUtils 就能自动拿到 ID
                            SecurityContextHolder.getContext().setAuthentication(authentication);
                            request.removeAttribute(AUTH_ERROR_ATTRIBUTE);

                            // MDC：认证通过后写入 userId，供 JSON 日志按用户关联（未认证请求不写入）
                            Long userId = SecurityUtils.getUserId();
                            if (userId != null) {
                                MDC.put(MDC_USER_ID_KEY, userId.toString());
                            }
                            }
                        }
                    }
                } catch (ExpiredJwtException e) {
                    request.setAttribute(AUTH_ERROR_ATTRIBUTE, CommonErrorCode.AUTH_TOKEN_EXPIRED);
                } catch (DependencyUnavailableException | DataAccessResourceFailureException | QueryTimeoutException | org.springframework.transaction.CannotCreateTransactionException | AuthenticationServiceException e) {
                    logger.error("认证依赖不可用", e);
                    ErrorResponses.write(response, 503, CommonErrorCode.AUTH_DEPENDENCY_UNAVAILABLE);
                    return;
                } catch (JwtException | IllegalArgumentException | AuthenticationException e) {
                    logger.debug("登录凭据验证未通过");
                } catch (Exception e) {
                    if (com.musealgo.oj.common.config.InfrastructureFailures.isUnavailable(e)) {
                        logger.error("认证依赖不可用", e);
                        ErrorResponses.write(response, 503, CommonErrorCode.AUTH_DEPENDENCY_UNAVAILABLE);
                        return;
                    }
                    logger.error("认证处理失败", e);
                    ErrorResponses.write(response, 500, CommonErrorCode.INTERNAL_ERROR);
                    return;
                }
            }

        // 2. 放行逻辑（用 try-finally 包裹）
            chain.doFilter(request, response);
        } finally {
            // 3. 清理逻辑（请求处理完后，清空保险箱，防止内存泄漏和串号）
            SecurityContextHolder.clearContext();
            MDC.remove(MDC_USER_ID_KEY);
        }
    }

    private static boolean isCoursewareStaticRequest(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String prefix = path.startsWith("/api/coursewares/")
                ? "/api/coursewares/"
                : path.startsWith("/coursewares/") ? "/coursewares/" : null;
        if (prefix == null) return false;
        String remainder = path.substring(prefix.length());
        int separator = remainder.indexOf('/');
        return separator > 0
                && COURSEWARE_OUTPUT_DIRECTORY.matcher(remainder.substring(0, separator)).matches();
    }
}
