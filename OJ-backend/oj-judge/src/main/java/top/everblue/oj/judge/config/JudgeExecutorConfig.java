package top.everblue.oj.judge.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 判题链路专用线程池（高并发方案 A1）。
 *
 * <h3>为什么独立于 @Scheduled 调度线程池</h3>
 * Spring Boot 默认 {@code spring.task.scheduling.pool.size=1}，若不拆分，
 * 判题执行期间看门狗 / Outbox / 排名等所有定时任务都会停摆（评审 P-1）。
 * 本配置将"判题执行"迁移到独立线程池，调度线程只负责快速领取与投递。
 *
 * <h3>背压语义</h3>
 * 队列有界（默认 200），拒绝策略 CallerRuns：线程池满载时在调用线程执行，
 * 宁可降速也绝不丢弃判题任务（比赛高峰丢任务不可接受）。
 * 领取侧（claimBatch）的批量上限与核心线程数对齐，避免瞬时领取过量积压。
 *
 * <h3>线程池</h3>
 * <ul>
 *   <li>{@code judgeExecutor}：判题任务执行池（A1/A2），每任务 = 一次完整判题</li>
 *   <li>{@code judgeCaseExecutor}：单个提交内用例并行执行池（A3-1），
 *       固定线程数 = {@code oj.judge.worker.case-parallelism}（默认 4，对齐 Judge0 槽位）</li>
 * </ul>
 */
@Configuration
public class JudgeExecutorConfig {

    /** 判题任务执行池：每任务 = 一次完整判题（加载数据 → 逐用例 Judge0 → 回写） */
    @Bean("judgeExecutor")
    public ThreadPoolTaskExecutor judgeExecutor(
            @Value("${oj.judge.worker.core-threads:8}") int coreThreads,
            @Value("${oj.judge.worker.max-threads:16}") int maxThreads,
            @Value("${oj.judge.worker.queue-capacity:200}") int queueCapacity,
            MeterRegistry meterRegistry) {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(coreThreads);
        pool.setMaxPoolSize(maxThreads);
        pool.setQueueCapacity(queueCapacity);
        pool.setThreadNamePrefix("judge-worker-");
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(60);
        pool.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        pool.initialize();

        // C3：判题池活跃度指标（Prometheus）
        io.micrometer.core.instrument.Gauge.builder("judge.pool.active", pool,
                        ThreadPoolTaskExecutor::getActiveCount)
                .description("判题线程池活跃线程数")
                .register(meterRegistry);
        io.micrometer.core.instrument.Gauge.builder("judge.pool.queue", pool,
                        p -> p.getThreadPoolExecutor().getQueue().size())
                .description("判题线程池队列深度")
                .register(meterRegistry);
        return pool;
    }

    /** 用例并行执行池：单个提交内并发调 Judge0 的用例数上限（A3-1） */
    @Bean("judgeCaseExecutor")
    public Executor judgeCaseExecutor(
            @Value("${oj.judge.worker.case-parallelism:4}") int parallelism) {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(Math.max(1, parallelism));
        pool.setMaxPoolSize(Math.max(1, parallelism));
        pool.setQueueCapacity(1000); // 用例任务小且快，容量给足，拒绝策略兜底
        pool.setThreadNamePrefix("judge-case-");
        pool.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        pool.initialize();
        return pool;
    }
}
