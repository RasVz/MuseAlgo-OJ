package top.everblue.oj.judge.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.everblue.oj.judge.entity.OutboxEvent;
import top.everblue.oj.judge.mapper.OutboxEventMapper;

import java.util.List;

/**
 * Outbox 发布器 —— 定时轮询 outbox_event 表，将 PENDING 事件投递到 RabbitMQ。
 *
 * <h3>事务性 Outbox 模式</h3>
 * <ol>
 *   <li>判题 Worker 在同一数据库事务中写入 outbox_event + 更新 judge_task 状态</li>
 *   <li>本发布器定时轮询 PENDING 事件</li>
 *   <li>投递成功 → 标记 PUBLISHED</li>
 *   <li>投递失败 → 指数退避重试（最多 5 次），超限标记 FAILED</li>
 * </ol>
 * 这保证了 "至少一次" 的消息送达语义。
 *
 * <h3>Opt-in</h3>
 * 仅当 {@code oj.messaging.rabbitmq-enabled=true} 时才启用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "oj.messaging", name = "rabbitmq-enabled", havingValue = "true")
public class OutboxPublisher {

    private final OutboxEventMapper outboxEventMapper;
    private final RabbitTemplate rabbitTemplate;

    private static final int POLL_LIMIT = 50;
    private static final int MAX_RETRIES = 5;
    private static final int BASE_BACKOFF_SECONDS = 10;

    /**
     * 轮询 outbox_event，间隔可通过 {@code oj.messaging.outbox.poll-interval-ms} 配置，默认 5000ms。
     */
    @Scheduled(fixedDelayString = "${oj.messaging.outbox.poll-interval-ms:5000}")
    public void publishPending() {
        List<OutboxEvent> events;
        try {
            events = outboxEventMapper.selectPendingEvents(POLL_LIMIT);
        } catch (Exception e) {
            log.error("【Outbox】查询 PENDING 事件失败: {}", e.getMessage());
            return;
        }

        if (events.isEmpty()) {
            return;
        }

        log.info("【Outbox】本批次 {} 条 PENDING 事件待投递", events.size());

        for (OutboxEvent event : events) {
            try {
                // 投递到 oj.event 交换机，routingKey = event_type
                // 例如 submission.judged → 被 oj.rank.update 队列消费
                rabbitTemplate.convertAndSend(
                        "oj.event",
                        event.getEventType(),
                        event.getPayload()
                );

                outboxEventMapper.markPublished(event.getId());
                log.debug("【Outbox】投递成功 eventId={}, type={}", event.getId(), event.getEventType());

            } catch (Exception e) {
                int backoff = BASE_BACKOFF_SECONDS * (1 << Math.min(event.getRetryCount(), 4));
                outboxEventMapper.markFailed(
                        event.getId(),
                        e.getMessage(),
                        backoff,
                        MAX_RETRIES
                );
                log.warn("【Outbox】投递失败 eventId={}, retry={}, backoff={}s, err={}",
                        event.getId(), event.getRetryCount() + 1, backoff, e.getMessage());
            }
        }
    }
}
