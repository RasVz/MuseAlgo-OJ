package com.musealgo.oj.system.controller;

import com.musealgo.oj.common.exception.ErrorResponses;
import io.jsonwebtoken.JwtException;
import org.springframework.security.authentication.AuthenticationServiceException;
import com.musealgo.oj.common.exception.CommonErrorCode;
import com.musealgo.oj.system.error.SystemErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.musealgo.oj.common.annotation.RateLimit;
import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.common.utils.JwtUtils;
import com.musealgo.oj.system.entity.User;
import com.musealgo.oj.system.service.JwtBlacklistService;
import com.musealgo.oj.system.service.LoginFailureGuard;
import com.musealgo.oj.system.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.util.HashMap;
import java.time.LocalDateTime;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 认证入口：注册、登录、刷新 token、注销。
 * <p>
 * 认证本身委托给 Spring Security 的 {@link AuthenticationManager}（密码比对、用户加载都在那边），
 * 本类只负责 token 的签发与生命周期管理。不要把密码校验逻辑搬进来——绕过
 * {@code AuthenticationManager} 就绕过了 BCrypt 比对与账号状态检查。
 * </p>
 * <p>
 * 三个关键约定：
 * </p>
 * <ol>
 *   <li><strong>refresh 必须查注销黑名单。</strong> 只验签的话，用户「注销」后仍可以拿旧 refresh
 *       换出新的 access，等于注销形同虚设。这是无状态 JWT 方案里注销能力的唯一支点。</li>
 *   <li><strong>注销是幂等的、尽力而为的。</strong> 无 token、token 已过期、重复调用都返回成功，
 *       因为此时客户端无论如何都会清空本地 token；反过来抛错只会让用户在「token 本来就过期」
 *       的场景看到一次莫名的报错。黑名单 TTL 按 token 剩余有效期设置，
 *       过期 token 进黑名单既无意义又会浪费内存。</li>
 *   <li><strong>返回用户信息前必须擦除密码</strong>（{@code setPassword(null)}）。
 *       返回的是 User 实体而非 VO，所以这一步不能省，也不能指望前端不用这个字段。</li>
 * </ol>
 * <p>
 * 注册与登录、刷新都按 IP 限流（{@code byIp = true}）：这几个接口在白名单里<strong>不要求登录</strong>，
 * 只能靠 IP 维度防住暴力破解与批量注册。注意限流键用 IP 而非用户 ID——此时用户身份还不可信。
 * </p>
 * <p>
 * 登录认证失败返回业务码 401 和统一文案，前端在登录页展示错误；它不代表已有会话过期。
 * </p>
 */
@Slf4j
@Tag(name = "认证管理", description = "登录、注册、Token 刷新、注销")
@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthController {

    private final UserService userService;

    private final AuthenticationManager authenticationManager;

    private final JwtUtils jwtUtils; // 你的工具类

    private final JwtBlacklistService jwtBlacklistService;

    private final LoginFailureGuard loginFailureGuard;

    @ExceptionHandler(AuthenticationException.class)
    public Result<String> handleAuthenticationFailure(AuthenticationException exception) {
        return ErrorResponses.failure(CommonErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    @ExceptionHandler(AuthenticationServiceException.class)
    public Result<String> handleAuthenticationServiceFailure(AuthenticationServiceException exception) {
        log.error("登录认证依赖失败", exception);
        return ErrorResponses.failure(CommonErrorCode.AUTH_DEPENDENCY_UNAVAILABLE);
    }

    @Operation(summary = "刷新 Token")
    @RateLimit(key = "authRefresh", time = 60, count = 30, byIp = true)
    @PostMapping("/refresh")
    public Result<String> refreshToken(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        if (!StringUtils.hasText(refreshToken)) return ErrorResponses.failure(CommonErrorCode.AUTH_REFRESH_INVALID);
        final String username;
        try {
            username = jwtUtils.extractUsername(refreshToken);
            if (!jwtUtils.validateRefreshToken(refreshToken, username)) {
                return ErrorResponses.failure(CommonErrorCode.AUTH_REFRESH_INVALID);
            }
        } catch (JwtException | IllegalArgumentException invalid) {
            return ErrorResponses.failure(CommonErrorCode.AUTH_REFRESH_INVALID);
        }
        // Dependency errors propagate as unavailable and still deny authorization.
        if (jwtBlacklistService.isRevoked(jwtUtils.extractJti(refreshToken))) {
            return ErrorResponses.failure(CommonErrorCode.AUTH_TOKEN_REVOKED);
        }
        User currentUser = userService.getOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (currentUser == null || (currentUser.getIsBanned() != null && currentUser.getIsBanned() == 1)
                || !jwtUtils.hasCurrentCredentialVersion(refreshToken, currentUser.getCredentialVersion())) {
            return ErrorResponses.failure(CommonErrorCode.AUTH_REFRESH_INVALID);
        }
        return Result.success(jwtUtils.generateAccessToken(username, credentialVersion(currentUser)));
    }

    @Operation(summary = "注销登录（服务端拉黑 access + refresh，尽力而为）")
    @PostMapping("/logout")
    public Result<String> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, String> body) {
        // 1. 拉黑 access token（从 Authorization 头解析）
        if (authorization != null && authorization.startsWith("Bearer ")) {
            revokeToken(authorization.substring(7));
        }
        // 2. 可选：一并拉黑 refresh token（防注销后 refresh 换新 access）
        if (body != null && StringUtils.hasText(body.get("refreshToken"))) {
            revokeToken(body.get("refreshToken"));
        }
        // 幂等：无 token / token 无效 / 重复注销一律返回成功（尽力而为语义）
        return Result.success("已退出登录");
    }

    /**
     * 拉黑单个 token（按其剩余有效期设置黑名单 TTL）。
     * token 无效/已过期时静默跳过（黑名单对已过期 token 无意义）。
     */
    private void revokeToken(String token) {
        try {
            String jti = jwtUtils.extractJti(token);
            if (jti != null) {
                jwtBlacklistService.revoke(jti, jwtUtils.getRemainingTtl(token));
            }
        } catch (Exception e) {
            log.debug("logout 忽略无效 token: {}", e.getMessage());
        }
    }

    @Operation(summary = "用户注册")
    @RateLimit(key = "authRegister", time = 60, count = 5, byIp = true)
    @PostMapping("/register")
    public Result<String> register(@RequestBody User user) {
        Integer code = userService.registerCheck(user);
        if (code == 1) return ErrorResponses.failure(SystemErrorCode.AUTH_USERNAME_TAKEN, "用户名已被使用");
        if (code == 2) return ErrorResponses.failure(SystemErrorCode.AUTH_EMAIL_TAKEN, "邮箱已被使用");

        boolean isSuccess = userService.register(user);
        return isSuccess ? Result.success("注册成功") : ErrorResponses.failure(CommonErrorCode.INTERNAL_ERROR, "注册失败");
    }

    @Operation(summary = "用户登录")
    @RateLimit(key = "authLogin", action = "登录", byIp = true, time = 60, count = 10,
            countProperty = "oj.security.login.ip-max-attempts",
            timeProperty = "oj.security.login.ip-window-seconds")
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, Object> loginData) {
        if (!(loginData.get("username") instanceof String username) || username.isBlank()
                || !(loginData.get("password") instanceof String password) || password.isBlank()
                || (loginData.get("rememberMe") != null && !(loginData.get("rememberMe") instanceof Boolean))) {
            return ErrorResponses.failure(SystemErrorCode.SYSTEM_INPUT_INVALID, "请输入有效的用户名和密码");
        }

        // 获取前端传来的【记住我】状态，默认为 false
        boolean rememberMe = Boolean.TRUE.equals(loginData.get("rememberMe"));

        log.debug("rememberMe: {}", rememberMe);

        // 0. 账号维度冷却（默认关闭，由 oj.security.login.account-max-failures 开启）
        //    与按 IP 的 @RateLimit 是两个口径：IP 额度防单机爆破，账号冷却防「换 IP 猜同一账号」。
        long cooling = loginFailureGuard.lockedSeconds(username);
        if (cooling > 0) {
            return ErrorResponses.failure(CommonErrorCode.AUTH_LOGIN_COOLDOWN, "账号或密码错误次数过多，请 " + cooling + " 秒后再试", Map.of("retryAfterSeconds", cooling));
        }

        // 1. 认证
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password)
            );
        } catch (AuthenticationServiceException e) {
            throw e;
        } catch (AuthenticationException e) {
            loginFailureGuard.recordFailure(username);
            throw e;
        }
        loginFailureGuard.clearFailures(username);

        User authenticatedUser = (User) authentication.getPrincipal();

        // 2. 生成 Token
        long credentialVersion = credentialVersion(authenticatedUser);
        String accessToken = jwtUtils.generateAccessToken(authenticatedUser.getUsername(), credentialVersion);

        // rememberMe 决定了 RefreshToken 是 1 天还是 7 天
        String refreshToken = jwtUtils.generateRefreshToken(authenticatedUser.getUsername(), rememberMe, credentialVersion);

        // 2.5 记录最后登录时间（管理端展示用）
        try {
            User lastLoginUpdate = new User();
            lastLoginUpdate.setId(authenticatedUser.getId());
            lastLoginUpdate.setLastLoginTime(LocalDateTime.now());
            userService.updateById(lastLoginUpdate);
        } catch (Exception e) {
            log.warn("记录最后登录时间失败 userId={}: {}", authenticatedUser.getId(), e.getMessage());
        }

        // 3. 擦除密码
        authenticatedUser.setPassword(null);

        // 4. 返回
        Map<String, Object> map = new HashMap<>();
        map.put("accessToken", accessToken);
        map.put("refreshToken", refreshToken);
        map.put("user", authenticatedUser);

        return Result.success("登录成功", map);
    }

    private long credentialVersion(User user) {
        return user.getCredentialVersion() == null ? 0L : user.getCredentialVersion();
    }
}
