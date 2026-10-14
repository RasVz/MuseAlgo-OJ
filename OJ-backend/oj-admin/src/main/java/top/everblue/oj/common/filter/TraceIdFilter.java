package top.everblue.oj.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求级 traceId（O8 可观测性）。
 * <p>
 * 把 traceId 放入 MDC 供日志关联（logback pattern 中 {@code %X{traceId:-}}），
 * 同时回写 {@code X-Trace-Id} 响应头，方便从前端/网关串联整条请求日志。
 * 支持透传调用方提供的 header（≤64 字符），否则自生成。
 * </p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";
    public static final String MDC_URI_KEY = "requestUri";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank() || traceId.length() > 64) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        }
        MDC.put(MDC_KEY, traceId);
        // 记录请求 URI，便于 JSON 日志按端点检索（userId 由 JwtAuthenticationTokenFilter 在认证后写入）
        MDC.put(MDC_URI_KEY, request.getRequestURI());
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            MDC.remove(MDC_URI_KEY);
        }
    }
}
