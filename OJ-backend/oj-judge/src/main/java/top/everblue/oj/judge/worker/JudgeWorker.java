package top.everblue.oj.judge.worker;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import top.everblue.oj.judge.entity.JudgeTask;
import top.everblue.oj.judge.metrics.JudgeMetrics;
import top.everblue.oj.judge.service.JudgeTaskService;

import io.micrometer.core.instrument.Timer;

import java.util.List;

/**
 * 后台判题 Worker —— 定时轮询 judge_task 表，批量领取后交给判题线程池并行执行。
 *
 * <h3>工作流程（高并发方案 A1/A2）</h3>
 * <ol>
 *   <li>每 2 秒轮询一次 judge_task（@Scheduled fixedDelay，专用调度线程池）</li>
 *   <li>原子批量领取 PENDING 任务（FOR UPDATE SKIP LOCKED，LIMIT batch-size）</li>
 *   <li>每个任务投递到独立判题线程池 {@code judgeExecutor} 异步并行判题，
 *       单任务失败不影响同批其他任务</li>
 *   <li>判题编排委托给 {@link JudgeOrchestrator}（加载数据 → 用例并行 Judge0 → 回写提交）</li>
 *   <li>成功后同一事务写入 Outbox 事件，失败走退避重试 / 标记 FAILED</li>
 * </ol>
 *
 * <h3>并发安全</h3>
 * 多实例部署时，MySQL 行级锁（FOR UPDATE SKIP LOCKED）
 * 保证同一任务只被一个 Worker 获取；RabbitMQ 唤醒消费者与定时轮询并行领取同样安全。
 *
 * <h3>背压</h3>
 * 判题线程池队列有界，满载时 CallerRuns 策略在调度线程内执行，
 * 宁可降速也绝不丢弃判题任务。
 */
@Slf4j
@Component
public class JudgeWorker {

    private final JudgeTaskService judgeTaskService;
    private final JudgeCompletionService judgeCompletionService;
    private final JudgeOrchestrator judgeOrchestrator;
    private final JudgeMetrics judgeMetrics;
    private final ThreadPoolTaskExecutor judgeExecutor;

    @Value("${oj.judge.worker.batch-size:8}")
    private int batchSize;

    public JudgeWorker(JudgeTaskService judgeTaskService,
                       JudgeCompletionService judgeCompletionService,
                       JudgeOrchestrator judgeOrchestrator,
                       JudgeMetrics judgeMetrics,
                       @Qualifier("judgeExecutor") ThreadPoolTaskExecutor judgeExecutor) {
        this.judgeTaskService = judgeTaskService;
        this.judgeCompletionService = judgeCompletionService;
        this.judgeOrchestrator = judgeOrchestrator;
        this.judgeMetrics = judgeMetrics;
        this.judgeExecutor = judgeExecutor;
    }

    /**
     * 定时轮询：按判题线程池空闲容量领取并投递。
     * <p>
     * poll-interval-ms 可通过 {@code oj.judge.worker.poll-interval-ms} 配置，默认 2000ms。
     * </p>
     * <p>
     * <b>背压设计（验证修正）</b>：领取量 = min(batch-size, 线程池空闲线程数)。
     * 线程池满载时本轮回空（任务留在 judge_task 表等下一轮），
     * 而不是 CallerRuns 占调度线程——否则判题高峰会把调度线程池吃光，
     * 看门狗 / Outbox / 排名等定时任务停摆（评审 P-1 的翻版）。
     * </p>
     */
    @Scheduled(fixedDelayString = "${oj.judge.worker.poll-interval-ms:2000}")
    public void pollAndJudge() {
        int active = judgeExecutor.getActiveCount();
        int capacity = judgeExecutor.getCorePoolSize() - active;
        if (capacity <= 0) {
            return; // 判题线程池满载，下一轮再来（背压留在任务表，不占调度线程）
        }

        List<JudgeTask> tasks;
        try {
            tasks = judgeTaskService.claimBatch(Math.min(batchSize, capacity));
        } catch (Exception e) {
            log.error("【判题Worker】批量领取任务异常: {}", e.getMessage());
            return;
        }

        if (tasks.isEmpty()) {
            return; // 无待处理任务，静默等待下一轮
        }

        for (JudgeTask task : tasks) {
            judgeExecutor.execute(() -> executeTask(task));
        }
    }

    /** 单个任务的完整判题流程（在判题线程池中执行） */
    private void executeTask(JudgeTask task) {
        // 判题池线程不继承请求 MDC，显式写入稳定 traceId 供 JSON 日志串联（领取→判题→回写）
        MDC.put("traceId", "judge-task-" + task.getId());
        try {
            log.info("【判题Worker】开始判题 taskId={}, submissionId={}, attempt={}",
                    task.getId(), task.getSubmissionId(), task.getAttemptCount());
            judgeMetrics.recordClaimed();
            Timer.Sample sample = judgeMetrics.startExecution();

            try {
                judgeOrchestrator.executeJudge(task.getSubmissionId());
                judgeCompletionService.completeWithOutbox(task);
                judgeMetrics.recordCompleted(sample);
            } catch (Exception e) {
                log.error("【判题Worker】判题失败 taskId={}: {}", task.getId(), e.getMessage());
                judgeTaskService.fail(task.getId(), e.getMessage());
                judgeMetrics.recordFailed(sample);
            }
        } finally {
            MDC.remove("traceId");
        }
    }
}
