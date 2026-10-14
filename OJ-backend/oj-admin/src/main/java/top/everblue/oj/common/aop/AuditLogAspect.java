package top.everblue.oj.common.aop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.system.entity.AuditLog;
import top.everblue.oj.system.entity.User;
import top.everblue.oj.system.service.AuditLogService;

import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * 审计日志切面 —— 拦截所有标注了 {@code @AuditLog} 的方法。
 *
 * <h3>拦截点（Join Point）</h3>
 * 本切面只拦截贴了 {@code @AuditLog} 注解的方法，不会影响其他接口性能。
 *
 * <h3>信息采集清单</h3>
 * <ol>
 *   <li><b>操作人</b>：从 SecurityContext 获取 userId 和 username</li>
 *   <li><b>请求 IP</b>：从 HttpServletRequest 获取（支持代理穿透）</li>
 *   <li><b>方法名</b>：全限定类名 + 方法名</li>
 *   <li><b>入参</b>：用 Jackson 序列化为 JSON 字符串（截断 2000 字符防止撑爆字段）</li>
 *   <li><b>耗时</b>：System.currentTimeMillis() 差值</li>
 *   <li><b>结果</b>：正常 = 1，异常 = 0 + 异常信息</li>
 * </ol>
 *
 * <h3>性能优化（重点）</h3>
 * <p>
 * 切面中<b>不做任何同步 IO</b>（不调 mapper、不写文件）。
 * 所有采集工作都是纯内存操作（纳秒级），然后调 {@code auditLogService.asyncInsert()}
 * 把日志实体丢给独立的线程池异步写库。业务线程在丢完任务后立刻返回，
 * 平均增加耗时 < 1ms（基本可忽略）。
 * </p>
 *
 * @see AuditLog
 * @see AuditLogService
 */
@Slf4j
@Aspect
@Component
public class AuditLogAspect {

    @Autowired
    private AuditLogService auditLogService;

    /**
     * Jackson 序列化器（复用 ObjectMapper，避免每次 new）。
     * 线程安全，可以声明为 static final。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 入参 JSON 字符串最大长度（防止超长参数撑爆 TEXT 字段） */
    private static final int MAX_PARAMS_LENGTH = 2000;

    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "password", "oldpassword", "newpassword", "confirmpassword",
            "token", "accesstoken", "refreshtoken", "secret", "apikey");

    /**
     * 环绕通知：切入所有标注了 @AuditLog 的方法。
     *
     * @param joinPoint 切点
     * @param auditLog  注解实例（从中读取 module / action）
     * @return 原方法返回值
     * @throws Throwable 原方法抛出的异常
     */
    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint joinPoint,
                         top.everblue.oj.common.annotation.AuditLog auditLog) throws Throwable {

        // ================== 第一阶段：纯内存采集（纳秒级） ==================

        // 1) 采集操作人信息
        Long userId = SecurityUtils.getUserId();
        String username = resolveUsername();

        // 2) 采集请求 IP（支持 Nginx / 代理穿透）
        String clientIp = getClientIp();

        // 3) 采集方法名（类全限定名.方法名）
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String methodName = signature.getDeclaringTypeName() + "." + signature.getName();

        // 4) 采集入参（序列化为 JSON，超长截断）
        String requestParams = truncate(toSafeJson(joinPoint.getArgs()), MAX_PARAMS_LENGTH);

        // 5) 开始计时
        long startTime = System.currentTimeMillis();

        // ================== 第二阶段：执行业务方法 ==================

        Object result;
        int status = 1;          // 默认：成功
        String errorMessage = null;

        try {
            result = joinPoint.proceed();   // 执行真正的 Controller 方法
        } catch (Throwable e) {
            status = 0;                     // 标记失败
            errorMessage = truncate(e.getClass().getName() + ": " + e.getMessage(), 500);
            throw e;                        // 异常继续向上抛，由 GlobalExceptionHandler 处理
        } finally {
            // 6) 计算耗时
            long executionTime = System.currentTimeMillis() - startTime;

            // ================== 第三阶段：异步写库（微秒级返回） ==================
            // 组装日志实体，丢给线程池 —— 业务线程此时已不受影响
            AuditLog logEntity = new AuditLog();
            logEntity.setUserId(userId);
            logEntity.setUsername(username);
            logEntity.setModule(auditLog.module());
            logEntity.setAction(auditLog.action());
            logEntity.setMethodName(methodName);
            logEntity.setRequestParams(requestParams);
            logEntity.setClientIp(clientIp);
            logEntity.setExecutionTime(executionTime);
            logEntity.setStatus(status);
            logEntity.setErrorMessage(errorMessage);
            logEntity.setCreateTime(LocalDateTime.now());

            // 异步写入：auditLogService.asyncInsert() 上打了 @Async，
            // 实际在 auditLogExecutor 线程池中执行，此调用立即返回
            auditLogService.asyncInsert(logEntity);
        }

        return result;
    }

    // ========================== 辅助方法 ==========================

    /**
     * 获取客户端真实 IP。
     * <p>
     * 优先从常见代理头（X-Forwarded-For、X-Real-IP 等）读取，
     * 防止 Nginx 反向代理后拿到的是 127.0.0.1。
     * </p>
     */
    /**
     * 从 SecurityContext 提取当前登录用户的用户名（LoginUser 或 User 主体，未登录返回 null）。
     */
    private String resolveUsername() {
        try {
            Object principal = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication().getPrincipal();
            if (principal instanceof User user) {
                return user.getUsername();
            }
        } catch (Exception ignored) {
            // 未登录或异常时返回 null
        }
        return null;
    }

    private String getClientIp() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return "unknown";
            }
            HttpServletRequest request = attributes.getRequest();

            // 按优先级依次尝试：代理头 → RemoteAddr
            String ip = request.getHeader("X-Forwarded-For");
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getHeader("X-Real-IP");
            }
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getHeader("Proxy-Client-IP");
            }
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getHeader("WL-Proxy-Client-IP");
            }
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getRemoteAddr();
            }

            // X-Forwarded-For 可能包含多个 IP（格式: client, proxy1, proxy2），取第一个
            if (ip != null && ip.contains(",")) {
                ip = ip.split(",")[0].trim();
            }

            return ip != null ? ip : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 将任意对象序列化为 JSON 字符串。
     * 序列化失败时返回占位字符串，不抛异常影响业务。
     */
    private String toSafeJson(Object obj) {
        try {
            JsonNode root = OBJECT_MAPPER.valueToTree(obj);
            maskSensitiveFields(root);
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("【审计日志】参数序列化失败: {}", e.getMessage());
            return "[序列化失败]";
        }
    }

    private void maskSensitiveFields(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<String> fieldNames = object.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                if (SENSITIVE_FIELDS.contains(fieldName.toLowerCase(Locale.ROOT))) {
                    object.put(fieldName, "******");
                } else {
                    maskSensitiveFields(object.get(fieldName));
                }
            }
        } else if (node.isArray()) {
            node.forEach(this::maskSensitiveFields);
        }
    }

    /**
     * 截断字符串到指定长度（超长尾部加 "..." 标记）。
     */
    private String truncate(String str, int maxLength) {
        if (str == null) {
            return null;
        }
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength) + "...(truncated)";
    }
}
