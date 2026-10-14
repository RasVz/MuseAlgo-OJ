package top.everblue.oj.system.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import top.everblue.oj.system.entity.AuditLog;
import top.everblue.oj.system.mapper.AuditLogMapper;
import top.everblue.oj.system.service.AuditLogService;

/**
 * 审计日志服务实现。
 * <p>
 * {@code @Async("auditLogExecutor")} 让 {@link #asyncInsert(AuditLog)}
 * 在独立的线程池中执行，业务线程无需等待 DB 写入完成。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogMapper auditLogMapper;

    /**
     * 异步写库。
     * <p>
     * <b>为什么 @Async 能解决性能问题？</b><br>
     * 没有 @Async 时，调用链是：<br>
     * 用户请求 → AOP 切面 → mapper.insert()（等 DB 响应 5~50ms）→ 返回用户<br>
     * <br>
     * 加了 @Async 后，调用链变成：<br>
     * 用户请求 → AOP 切面 → 丢任务给线程池（0ms 即返回）→ 返回用户<br>
     * 　　　　　　　　　　　　└→ 后台线程慢慢 insert，用户已收到响应<br>
     * </p>
     *
     * @param auditLog 待写入的审计日志
     */
    @Override
    @Async("auditLogExecutor")  // 使用 AuditLog 专用线程池，不占用默认线程池
    public void asyncInsert(AuditLog auditLog) {
        try {
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            // 异步写库失败时打错误日志，不向上抛异常
            // 因为此时业务请求早已返回，抛异常也没人能接住
            log.error("【审计日志】异步写入数据库失败。module={}, action={}, userId={}, err={}",
                    auditLog.getModule(), auditLog.getAction(),
                    auditLog.getUserId(), e.getMessage());
        }
    }

    @Override
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<AuditLog> selectPage(
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<AuditLog> page,
            com.baomidou.mybatisplus.core.conditions.Wrapper<AuditLog> wrapper) {
        return auditLogMapper.selectPage(page, wrapper);
    }
}
