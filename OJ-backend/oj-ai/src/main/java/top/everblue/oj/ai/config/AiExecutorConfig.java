package top.everblue.oj.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 模块线程池配置。
 * <p>
 * 用受管 Bean 而不是静态字段：Spring 会在应用关闭时自动调用 shutdown，
 * 避免后台线程把 JVM 卡住。并发数按实际 QPS 调整，必要时换成有界队列。
 */
@Configuration
public class AiExecutorConfig {

    @Bean("aiStreamExecutor")
    public ExecutorService aiStreamExecutor() {
        return Executors.newFixedThreadPool(8);
    }

    /**
     * AI 用量流水落库专用单线程池。
     * <p>
     * 与流式响应线程池隔离：DB 写入抖动（慢查询/锁等待）
     * 不会挤占流式推送线程，保证首字延迟稳定。
     * </p>
     */
    @Bean("aiUsageExecutor")
    public ExecutorService aiUsageExecutor() {
        return Executors.newSingleThreadExecutor();
    }
}
