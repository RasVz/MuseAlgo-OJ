package top.everblue.oj.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.filter.JwtAuthenticationTokenFilter;

/**
 * Spring Security 装配：无状态 JWT 鉴权链路 + 全站放行白名单。
 * <p>
 * 鉴权由 {@link JwtAuthenticationTokenFilter} 完成，它把解析出的用户塞进 SecurityContext，
 * 本类只负责「把过滤器插进链路」和「声明哪些路径不校验」。两者必须成对存在：只放行不插过滤器，
 * 过滤器不插只做放行，都会导致接口行为与预期不符。
 * </p>
 * <p>
 * <strong>白名单是安全边界，改动前必须确认影响面。</strong> 任意新增
 * {@code permitAll} 都会让该路径彻底绕过鉴权，而 list/detail 这类只读接口放行时还要确认返回体里
 * 没有夹带非公开字段。默认规则是 {@code anyRequest().authenticated()}，白名单之外一律要登录，
 * 新增接口时不要顺手补 permitAll。
 * </p>
 * <p>
 * CSRF 关闭与 {@link SessionCreationPolicy#STATELESS} 是同一个决定的两面：会话不落地、
 * 凭据由 Authorization 头携带，CSRF 攻击就拿不到可利用的自动附带凭据。开 Session 就必须把
 * CSRF 打开，不能只关一半。
 * </p>
 * <p>
 * 401 与 403 用 {@code writeJson} 写成与业务接口同构的 {@link Result} JSON，而不是框架默认的
 * HTML 错误页——前端拦截器统一按 body 解析，分成两种格式会让前端在未登录时先抛一次 JSON 解析异常。
 * 这里注意二者语义不同：401 是「没登录」，前端据此尝试静默刷新 token；403 是「登录了但没权限」，
 * 不该刷新，直接提示用户。
 * </p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @org.springframework.beans.factory.annotation.Value("${oj.security.api-docs-public:true}")
    private boolean publicApiDocs;

    // JWT 验证过滤器
    @Autowired
    private JwtAuthenticationTokenFilter jwtAuthenticationTokenFilter;

    @Autowired
    private ObjectMapper objectMapper;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 关闭 CSRF
                .csrf(AbstractHttpConfigurer::disable)
                // 禁用 Session
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 允许同源 iframe 嵌入（课件 Slidev 页面经 vite/nginx 同源代理后嵌入关卡页；
                // 若保持 DENY 会被浏览器拦截导致 PPT 无法在 iframe 内渲染）
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                // 未认证 → 401（前端会尝试刷新 Token / 清理登录态）；已认证但无权限 → 403
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) ->
                                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        Result.error(401, "请先登录")))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                                        Result.error(403, "无权访问该资源")))
                )

                .authorizeHttpRequests(auth -> auth
                        // === 白名单配置 (允许匿名访问) ===
                        .requestMatchers("/auth/login", "/auth/register").permitAll() // 登录注册
                        .requestMatchers("/auth/refresh").permitAll()
                        .requestMatchers("/auth/logout").permitAll() // 注销=尽力而为+幂等（无 token 也返回成功），匿名可调
                        .requestMatchers("/error").permitAll() // 【关键】防止报错时跳转 /error 产生 403
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN") // 运维可观测性端点
                        .requestMatchers("/metrics/batch").permitAll() // 前端体验埋点上报（匿名聚合，无敏感数据，@RateLimit byIp 限频）
                        .requestMatchers("/coursewares/**", "/api/coursewares/**").permitAll() // 本地 Slidev 静态课件
                        .requestMatchers("/uploads/**").permitAll() // 本地文件上传（COS 未配置时的降级存储）

                        // 公共接口 (首页公告、题目列表等)
                        .requestMatchers("/post/announcements", "/post/list").permitAll()
                        .requestMatchers("/comment/list").permitAll()
                        .requestMatchers("/problem-list/list").permitAll()
                        .requestMatchers("/problem/list").permitAll()
                        .requestMatchers("/exam-paper/list").permitAll()
                        .requestMatchers("/contest/list").permitAll()
                        .requestMatchers("/contest/upcoming-count").permitAll()
                        .requestMatchers("/submission/list").permitAll()

                        // 详细页
                        .requestMatchers(HttpMethod.GET, "/problem-list/{id:\\d+}").permitAll()
                        .requestMatchers(HttpMethod.GET, "/exam-paper/{id:\\d+}").permitAll()
                        .requestMatchers(HttpMethod.GET, "/problem/{id:\\d+}").permitAll()
                        .requestMatchers(HttpMethod.GET, "/contest/{id:\\d+}").permitAll()
                        .requestMatchers(HttpMethod.GET, "/post/{id:\\d+}").permitAll()
                        .requestMatchers(HttpMethod.GET, "/user/info/{id:\\d+}").permitAll()
                        .requestMatchers(HttpMethod.GET, "/user-relation/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/rank/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/user/rank/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/user/radar/{id:\\d+}").permitAll()
                        .requestMatchers(HttpMethod.GET, "/user/rating/history/{id:\\d+}").permitAll()
                        .requestMatchers(HttpMethod.GET, "/user/recent-ac/{id:\\d+}").permitAll()
                        .requestMatchers(HttpMethod.GET, "/user/recommend/{id:\\d+}").permitAll()
                        .requestMatchers(HttpMethod.GET, "/user/learning-plan/{id:\\d+}").permitAll()

                        // Swagger 文档 (如果有)
                        .requestMatchers("/doc.html", "/webjars/**", "/v3/api-docs/**")
                        .access(new org.springframework.security.web.access.expression.WebExpressionAuthorizationManager(
                                publicApiDocs ? "permitAll" : "hasRole('ADMIN')"))

                        // === 其他接口必须登录 ===
                        .anyRequest().authenticated()
                );

        // 2. 把 JWT 过滤器添加到链中！这是能不能识别 Token 的关键
        http.addFilterBefore(jwtAuthenticationTokenFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private void writeJson(HttpServletResponse response, int status, Result<?> result) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), result);
    }
}
