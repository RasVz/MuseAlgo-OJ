package top.everblue.oj.judge.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import top.everblue.oj.judge.mapper.JudgeTaskMapper;

import java.time.Duration;

/**
 * 判题链路指标（O8 可观测性闭环）。
 * <p>
 * Prometheus 指标：
 * <ul>
 *   <li>{@code judge_tasks_claimed_total} / {@code judge_tasks_completed_total} / {@code judge_tasks_failed_total}</li>
 *   <li>{@code judge_task_pending} — PENDING 队列深度 gauge（每次抓取实时查库）</li>
 *   <li>{@code judge_execution_seconds} — 单次判题耗时直方图</li>
 * </ul>
 * </p>
 */
@Component
public class JudgeMetrics {

    private final MeterRegistry meterRegistry;
    private final JudgeTaskMapper judgeTaskMapper;

    private final Counter claimedCounter;
    private final Counter completedCounter;
    private final Counter failedCounter;
    private final Timer executionTimer;

    public JudgeMetrics(MeterRegistry meterRegistry, JudgeTaskMapper judgeTaskMapper) {
        this.meterRegistry = meterRegistry;
        this.judgeTaskMapper = judgeTaskMapper;

        this.claimedCounter = meterRegistry.counter("judge.tasks.claimed", "phase", "poll");
        this.completedCounter = meterRegistry.counter("judge.tasks.completed");
        this.failedCounter = meterRegistry.counter("judge.tasks.failed");
        this.executionTimer = Timer.builder("judge.execution")
                .description("单次判题编排耗时（含 Judge0 调用与落库）")
                .publishPercentileHistogram()
                .maximumExpectedValue(Duration.ofSeconds(60))
                .register(meterRegistry);

        // PENDING 队列深度：抓取时实时查询，避免本地计数与 DB 漂移
        meterRegistry.gauge("judge.task.pending",
                this, JudgeMetrics::pendingTaskCount);
        // PROCESSING 在途任务数（C3：并发化后观察在途任务是否逼近线程池容量）
        meterRegistry.gauge("judge.task.processing",
                this, JudgeMetrics::processingTaskCount);
    }

    public Timer.Sample startExecution() {
        return Timer.start(meterRegistry);
    }

    public void recordClaimed() {
        claimedCounter.increment();
    }

    public void recordCompleted(Timer.Sample sample) {
        completedCounter.increment();
        sample.stop(executionTimer);
    }

    public void recordFailed(Timer.Sample sample) {
        failedCounter.increment();
        if (sample != null) {
            sample.stop(executionTimer);
        }
    }

    private long pendingTaskCount() {
        try {
            return judgeTaskMapper.countByStatus("PENDING");
        } catch (Exception e) {
            return -1; // 数据库异常时指标返回 -1，避免抓取报错
        }
    }

    private long processingTaskCount() {
        try {
            return judgeTaskMapper.countByStatus("PROCESSING");
        } catch (Exception e) {
            return -1; // 数据库异常时指标返回 -1，避免抓取报错
        }
    }
}
