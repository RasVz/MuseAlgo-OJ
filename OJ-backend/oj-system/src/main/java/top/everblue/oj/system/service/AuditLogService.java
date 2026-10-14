package top.everblue.oj.system.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import top.everblue.oj.system.entity.AuditLog;

/**
 * 审计日志服务 —— 提供异步写入能力，不阻塞业务线程。
 */
public interface AuditLogService {

    /**
     * 异步写入一条审计日志到数据库。
     * <p>
     * 由 {@link top.everblue.oj.common.aop.AuditLogAspect} 切面调用。
     * 方法本身通过 {@code @Async} 注解在独立线程池中执行，
     * 不会拖慢业务接口的响应时间。
     * </p>
     *
     * @param auditLog 待写入的审计日志实体
     */
    void asyncInsert(AuditLog auditLog);

    /**
     * 审计日志分页查询（管理台用）。
     */
    Page<AuditLog> selectPage(Page<AuditLog> page, Wrapper<AuditLog> wrapper);
}
