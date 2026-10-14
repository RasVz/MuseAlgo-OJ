package top.everblue.oj.system.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.everblue.oj.common.annotation.RateLimit;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.utils.JwtUtils;
import top.everblue.oj.system.entity.User;
import top.everblue.oj.system.service.JwtBlacklistService;
import top.everblue.oj.system.service.UserService;

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
 * 登录返回 401 与 500 的区分也很重要：{@code authenticate} 抛出的异常未捕获时，
 * 由全局异常处理器转成 401，前端据此清理登录态并回到登录页。
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

    @Operation(summary = "刷新 Token")
    @RateLimit(key = "authRefresh", time = 60, count = 30, byIp = true)
    @PostMapping("/refresh")
    public Result<String> refreshToken(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");

        // 1. 校验 refreshToken 是否有效
        // 需要处理异常，比如 Token 过期会抛异常
        try {
            if (jwtUtils.validateToken(refreshToken, jwtUtils.extractUsername(refreshToken))) {

                // 1.5 注销检查：refresh jti 已在黑名单 → 拒绝刷新，要求重新登录
                //（防"注销后拿旧 refresh 换新 access"绕过注销）
                if (jwtBlacklistService.isRevoked(jwtUtils.extractJti(refreshToken))) {
                    return Result.error(401, "登录已注销，请重新登录");
                }

                // 2. 获取用户名
                String username = jwtUtils.extractUsername(refreshToken);

                // 3. 生成新的 Access Token
                String newAccessToken = jwtUtils.generateAccessToken(username);

                // 4. 返回
                return Result.success(newAccessToken);
            }
        } catch (Exception e) {
            return Result.error(401, "Refresh Token 失效，请重新登录");
        }

        return Result.error(401, "无效的 Token");
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
        if (code == 1) return Result.error("用户名已被使用");
        if (code == 2) return Result.error("邮箱已被使用");

        boolean isSuccess = userService.register(user);
        return isSuccess ? Result.success("注册成功") : Result.error("注册失败");
    }

    @Operation(summary = "用户登录")
    @RateLimit(key = "authLogin", time = 60, count = 10, byIp = true)
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, Object> loginData) {
        String username = (String) loginData.get("username");
        String password = (String) loginData.get("password");

        // 获取前端传来的【记住我】状态，默认为 false
        boolean rememberMe = loginData.containsKey("rememberMe") && (boolean) loginData.get("rememberMe");

        log.debug("rememberMe: {}", rememberMe);

        // 1. 认证
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, password)
        );

        User authenticatedUser = (User) authentication.getPrincipal();

        // 2. 生成 Token
        String accessToken = jwtUtils.generateAccessToken(authenticatedUser.getUsername());

        // rememberMe 决定了 RefreshToken 是 1 天还是 7 天
        String refreshToken = jwtUtils.generateRefreshToken(authenticatedUser.getUsername(), rememberMe);

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
}
