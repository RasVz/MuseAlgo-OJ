package top.everblue.oj.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作审计日志注解。
 * <p>
 * 标注在 Controller 方法上，AOP 切面会自动拦截并记录操作流水到 audit_log 表。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * @AuditLog(module = "user", action = "delete_user")
 * @DeleteMapping("/{id}")
 * public Result<?> deleteUser(@PathVariable Long id) { ... }
 * }</pre>
 *
 * <h3>底层逻辑（大白话版）</h3>
 * <p>
 * Spring AOP 的魔法叫<b>动态代理</b>。简单说就是 Spring 在启动时，发现你的
 * Controller 方法上贴了 @AuditLog，于是悄悄帮你生成了一个"代理对象"。
 * 这个代理对象长得和你的 Controller 一模一样，但它会在调用你真正的方法之前/之后，
 * 插入切面里的增强逻辑（记录日志）。这就是"不改原有代码就能增强功能"的本质。
 * </p>
 * <p>
 * 具体到 Spring Boot 默认使用 <b>CGLIB 动态代理</b>（通过继承目标类生成子类），
 * 这是基于字节码生成的技术，不需要目标类实现接口。
 * </p>
 *
 * @see top.everblue.oj.common.aop.AuditLogAspect
 */
@Target(ElementType.METHOD)        // 只贴方法上
@Retention(RetentionPolicy.RUNTIME) // 运行时通过反射读取
public @interface AuditLog {

    /**
     * 操作模块。
     * 例如 "user"、"problem"、"contest"、"class"。
     */
    String module();

    /**
     * 操作动作。
     * 例如 "delete_user"、"reset_password"、"update_problem"。
     */
    String action();
}
