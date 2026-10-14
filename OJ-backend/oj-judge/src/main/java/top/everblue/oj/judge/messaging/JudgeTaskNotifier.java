package top.everblue.oj.judge.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 判题任务唤醒器：任务落库后向 RabbitMQ 发一条轻量唤醒消息。
 *
 * <h3>定位</h3>
 * 这不是任务分发的主链路——judge_task 表 + SKIP LOCKED 仍然是唯一的
 * 任务来源与并发仲裁者。本消息只是"有任务了"的提示，让 Worker 毫秒级响应，
 * 把 DB 轮询（2s 间隔）降级为兜底路径。
 *
 * <h3>可靠性</h3>
 * 发布失败（broker 宕机/网络抖动）只影响本次唤醒的及时性，
 * DB 轮询仍会在下一个周期拾起任务，因此这里只记日志、绝不抛异常。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "oj.messaging", name = "rabbitmq-enabled", havingValue = "true")
public class JudgeTaskNotifier {

    private final RabbitTemplate rabbitTemplate;

    /** 发布唤醒消息（best-effort，失败不影响提交主流程） */
    public void notifyNewTask(Long submissionId) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitJudgeTopologyConfiguration.JUDGE_EXCHANGE,
                    "submission.request",
                    String.valueOf(submissionId)
            );
            log.debug("【判题唤醒】已发布 submissionId={}", submissionId);
        } catch (Exception e) {
            log.warn("【判题唤醒】发布失败（DB 轮询将兜底处理）submissionId={}, err={}",
                    submissionId, e.getMessage());
        }
    }
}
