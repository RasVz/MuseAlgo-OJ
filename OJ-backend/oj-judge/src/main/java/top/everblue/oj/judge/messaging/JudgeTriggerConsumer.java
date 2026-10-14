package top.everblue.oj.judge.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import top.everblue.oj.judge.worker.JudgeWorker;

/**
 * 判题唤醒消费者：收到"有新提交"消息后，触发一次与定时轮询完全相同的领取-判题流程。
 *
 * <h3>为什么不按 submissionId 精确处理</h3>
 * 消息只是提示而非任务本体。{@code claim()} 的原子性（FOR UPDATE SKIP LOCKED）
 * 保证无论定时轮询还是本消费者触发多少次，同一任务只会被一个执行路径获取，
 * 因此重复消息、消息乱序、消息丢失都不会导致重复判题或漏判。
 *
 * <h3>异常处理</h3>
 * 触发失败不 requeue（消息仅是一次性提示，DB 轮询会兜底），
 * 避免无意义的消息堆积进入死信队列。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "oj.messaging", name = "rabbitmq-enabled", havingValue = "true")
public class JudgeTriggerConsumer {

    private final JudgeWorker judgeWorker;

    // A1：默认 concurrency=1，多条消息串行唤醒，全局判题并发受限；
    // 扩到 4~8 与判题线程池对齐（claim 原子性保证并发领取不重复）
    @RabbitListener(queues = RabbitJudgeTopologyConfiguration.JUDGE_QUEUE,
            concurrency = "${oj.judge.worker.trigger-concurrency:4}")
    public void onSubmissionRequested(String submissionId) {
        log.debug("【判题唤醒】收到触发消息 submissionId={}", submissionId);
        try {
            judgeWorker.pollAndJudge();
        } catch (Exception e) {
            // 任务仍安全地留在 judge_task 表中，由定时轮询兜底
            log.warn("【判题唤醒】触发判题失败（等待轮询兜底）submissionId={}, err={}",
                    submissionId, e.getMessage());
        }
    }
}
