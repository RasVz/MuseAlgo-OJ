package top.everblue.oj.judge.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.everblue.oj.judge.entity.OutboxEvent;
import top.everblue.oj.judge.mapper.OutboxEventMapper;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Outbox 归档任务 —— 定期清理 outbox_event 表，防止 PUBLISHED / FAILED 永久累积。
 * <p>
 * 与 {@link OutboxPublisher} 同模块（oj-judge，数据归属方），启用条件一致
 * （仅 {@code oj.messaging.rabbitmq-enabled=true} 时生效）。默认每天凌晨 3:30 低峰执行，
 * cron 可通过 {@code oj.messaging.outbox.archive-cron} 配置。
 * </p>
 *
 * <h3>三个清理批次（每批 LIMIT archive-batch-size，循环直到取空，避免单事务过大）</h3>
 * <ol>
 *   <li>PUBLISHED 且 published_at 超过 retention-days（默认 7 天）→ 搬入 outbox_event_archive + 删除原记录；</li>
 *   <li>FAILED 且 created_at 超过 failed-retention-days（默认 30 天）→ 搬入归档表 + 删除原记录；</li>
 *   <li>卡死 PENDING（next_attempt_at 超过 1 天仍未发出）→ 先标记 FAILED（last_error='ARCHIVED_AS_STUCK'），
 *       下一轮按 FAILED 规则清理。</li>
 * </ol>
 * 每个批次（取数 + 搬移 + 删除 / 标记）在单个事务内完成；
 * 搬入归档保留审计痕迹，归档条件基于「已投递/已放弃」而非「已消费」，不误删在途事件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "oj.messaging", name = "rabbitmq-enabled", havingValue = "true")
public class OutboxArchiveTask {

    private final OutboxEventMapper outboxEventMapper;
    private final PlatformTransactionManager transactionManager;

    @Value("${oj.messaging.outbox.retention-days:7}")
    private int retentionDays;

    @Value("${oj.messaging.outbox.failed-retention-days:30}")
    private int failedRetentionDays;

    @Value("${oj.messaging.outbox.archive-batch-size:200}")
    private int archiveBatchSize;

    /** 卡死 PENDING 判定阈值：超过该天数未发出即视为死信 */
    private static final int STUCK_PENDING_DAYS = 1;
    /** 卡死事件标记 FAILED 时的 last_error */
    private static final String STUCK_ERROR_MARK = "ARCHIVED_AS_STUCK";

    /**
     * 定时归档入口：三个清理批次顺序执行。
     */
    @Scheduled(cron = "${oj.messaging.outbox.archive-cron:0 30 3 * * *}")
    public void archiveExpiredEvents() {
        log.info("【Outbox归档】任务开始: retentionDays={}, failedRetentionDays={}, batchSize={}",
                retentionDays, failedRetentionDays, archiveBatchSize);
        LocalDateTime now = LocalDateTime.now();
        int published = archiveInBatches(OutboxEvent.STATUS_PUBLISHED, now.minusDays(retentionDays));
        int failed = archiveInBatches(OutboxEvent.STATUS_FAILED, now.minusDays(failedRetentionDays));
        int stuck = markStuckPendingAsFailed(now.minusDays(STUCK_PENDING_DAYS));
        log.info("【Outbox归档】任务完成: PUBLISHED 归档 {} 条, FAILED 归档 {} 条, 卡死 PENDING 标记 FAILED {} 条",
                published, failed, stuck);
    }

    /** 循环处理某状态过期事件：每批取 archiveBatchSize 条，事务内搬入归档表并删除原记录，取空为止 */
    private int archiveInBatches(String status, LocalDateTime cutoff) {
        int total = 0;
        while (true) {
            int batch = archiveOneBatch(status, cutoff);
            if (batch == 0) {
                break;
            }
            total += batch;
            log.debug("【Outbox归档】status={} 本批归档 {} 条，累计 {} 条", status, batch, total);
        }
        return total;
    }

    /** 单批归档：同一事务内「查询过期 → 逐条搬入归档表 → 删除原记录」 */
    private int archiveOneBatch(String status, LocalDateTime cutoff) {
        return inTransaction(() -> {
            List<OutboxEvent> expired = outboxEventMapper.selectExpiredForArchive(status, cutoff, archiveBatchSize);
            for (OutboxEvent event : expired) {
                outboxEventMapper.insertArchive(event);
                outboxEventMapper.deleteByIds(Collections.singletonList(event.getId()));
            }
            return expired.size();
        });
    }

    /** 卡死 PENDING → 标记 FAILED（下一轮按 FAILED 规则归档），单事务内完成 */
    private int markStuckPendingAsFailed(LocalDateTime cutoff) {
        return inTransaction(() -> {
            List<OutboxEvent> stuck = outboxEventMapper.selectExpiredForArchive(
                    OutboxEvent.STATUS_PENDING, cutoff, archiveBatchSize);
            for (OutboxEvent event : stuck) {
                outboxEventMapper.markStuckAsFailed(event.getId(), STUCK_ERROR_MARK);
                log.warn("【Outbox归档】卡死 PENDING 标记 FAILED eventId={}, type={}",
                        event.getId(), event.getEventType());
            }
            return stuck.size();
        });
    }

    private <T> T inTransaction(Supplier<T> action) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(status -> action.get());
    }
}
