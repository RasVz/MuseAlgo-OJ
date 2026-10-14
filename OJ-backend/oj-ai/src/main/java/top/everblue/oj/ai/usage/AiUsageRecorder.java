package top.everblue.oj.ai.usage;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.everblue.oj.ai.entity.AiUsage;
import top.everblue.oj.ai.mapper.AiUsageMapper;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;

/**
 * AI 用量异步记录器。
 * <p>
 * 写库走独立的单线程 executor，绝不阻塞流式响应主链路；
 * 记录失败只记日志——流水是审计数据，不能反过来影响答疑可用性。
 * </p>
 */
@Slf4j
@Service
public class AiUsageRecorder {

    private final AiUsageMapper aiUsageMapper;
    private final ExecutorService aiUsageExecutor;
    private final MeterRegistry meterRegistry;
    private final Counter tokenCounter;
    private final Timer latencyTimer;

    @Value("${ai.model:deepseek-chat}")
    private String model;

    public AiUsageRecorder(AiUsageMapper aiUsageMapper,
                           @Qualifier("aiUsageExecutor") ExecutorService aiUsageExecutor,
                           MeterRegistry meterRegistry) {
        this.aiUsageMapper = aiUsageMapper;
        this.aiUsageExecutor = aiUsageExecutor;
        this.meterRegistry = meterRegistry;
        this.tokenCounter = meterRegistry.counter("ai.tokens.total");
        this.latencyTimer = Timer.builder("ai.request.latency")
                .description("AI 请求端到端耗时")
                .register(meterRegistry);
    }

    /**
     * 异步落一条流水。
     *
     * @param estimated true=token 按字符估算，false=API 返回精确值
     */
    public void record(Long userId, Long problemId, String scene,
                       int promptTokens, int completionTokens, boolean estimated,
                       long latencyMs, String status, String detail) {
        // 指标先于异步落库：即时可见，且不依赖 DB
        meterRegistry.counter("ai.requests.total", "outcome", status).increment();
        tokenCounter.increment(promptTokens + completionTokens);
        latencyTimer.record(java.time.Duration.ofMillis(latencyMs));

        aiUsageExecutor.execute(() -> {
            try {
                AiUsage usage = new AiUsage();
                usage.setUserId(userId);
                usage.setProblemId(problemId);
                usage.setScene(scene == null ? "ASK" : scene);
                usage.setModel(model);
                usage.setPromptTokens(promptTokens);
                usage.setCompletionTokens(completionTokens);
                usage.setTotalTokens(promptTokens + completionTokens);
                usage.setTokensEstimated(estimated);
                usage.setLatencyMs(latencyMs);
                usage.setStatus(status);
                usage.setDetail(truncate(detail));
                usage.setCreatedAt(LocalDateTime.now());
                aiUsageMapper.insert(usage);
            } catch (Exception e) {
                log.warn("【AI用量】流水写入失败 status={}, err={}", status, e.getMessage());
            }
        });
    }

    private static String truncate(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() > 500 ? detail.substring(0, 500) : detail;
    }
}
