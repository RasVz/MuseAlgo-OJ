package top.everblue.oj.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池配置。
 * <p>
 * <b>为什么需要专用线程池？</b><br>
 * 审计日志的 DB 写入不应该阻塞业务请求的响应。
 * 如果直接在 AOP 切面里同步调用 {@code mapper.insert()}，
 * 相当于每次请求多了一次数据库 IO，接口响应时间平白增加了 5~50ms。
 * </p>
 * <p>
 * 通过 {@code @Async("auditLogExecutor")} 把日志写入丢给这个线程池，
 * 业务线程立刻返回响应给用户，日志写入在后台异步完成。
 * 即使线程池满了（队列满 + 达到最大线程数），
 * 也采用 CallerRunsPolicy 兜底 —— 由业务线程自己执行写入，不会丢日志。
 * </p>
 */
@Configuration
@EnableAsync  // 开启 Spring 异步注解支持，让 @Async 生效
public class AsyncConfig {

    /**
     * 审计日志专用线程池。
     * <ul>
     *   <li>核心线程 2：平时只有 2 个线程待命，节省资源</li>
     *   <li>最大线程 5：日志量突增时最多扩展到 5 个</li>
     *   <li>队列容量 200：高峰期可堆积 200 个待写入任务</li>
     *   <li>CallerRunsPolicy：线程池和队列都满时不丢弃任务，由提交者线程自行执行</li>
     * </ul>
     */
    @Bean("auditLogExecutor")
    public Executor auditLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);                            // 核心线程数
        executor.setMaxPoolSize(5);                             // 最大线程数
        executor.setQueueCapacity(200);                         // 缓冲队列容量
        executor.setKeepAliveSeconds(60);                       // 空闲线程存活时间
        executor.setThreadNamePrefix("audit-log-");             // 线程名前缀，方便排查
        executor.setRejectedExecutionHandler(
                new ThreadPoolExecutor.CallerRunsPolicy());     // 拒绝策略：交给调用线程
        executor.setWaitForTasksToCompleteOnShutdown(true);     // 优雅停机：等待任务完成
        executor.setAwaitTerminationSeconds(30);                // 最多等 30 秒
        executor.initialize();
        return executor;
    }
}
