package top.everblue.oj.judge.worker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import top.everblue.oj.judge.entity.OutboxEvent;
import top.everblue.oj.judge.mapper.OutboxEventMapper;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Outbox 归档任务单测（2026-08-14 方案第 3 节）。
 * <p>
 * 事务模板走真实 {@link TransactionTemplate}（mock PlatformTransactionManager 的
 * getTransaction 返回 null 即空事务），Mapper 全部 mock：
 * 验证「老 PUBLISHED / 老 FAILED / 卡死 PENDING」被清理、新事件不受影响。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class OutboxArchiveTaskTest {

    @Mock
    private OutboxEventMapper outboxEventMapper;
    @Mock
    private PlatformTransactionManager transactionManager;

    private OutboxArchiveTask task;

    @BeforeEach
    void setUp() {
        // mock 空事务：getTransaction 返回 null 即可让 TransactionTemplate 走完回调
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(null);
        task = new OutboxArchiveTask(outboxEventMapper, transactionManager);
        ReflectionTestUtils.setField(task, "retentionDays", 7);
        ReflectionTestUtils.setField(task, "failedRetentionDays", 30);
        ReflectionTestUtils.setField(task, "archiveBatchSize", 200);
    }

    private OutboxEvent event(String id, String status) {
        OutboxEvent e = new OutboxEvent();
        e.setId(id);
        e.setAggregateType("submission");
        e.setAggregateId(1L);
        e.setEventType("submission.judged");
        e.setPayload("{\"submissionId\":1}");
        e.setStatus(status);
        e.setRetryCount(0);
        e.setCreatedAt(LocalDateTime.now().minusDays(60));
        return e;
    }

    @Test
    void archivesExpiredPublishedFailedAndStuckPending() {
        OutboxEvent oldPublished = event(UUID.randomUUID().toString(), OutboxEvent.STATUS_PUBLISHED);
        OutboxEvent oldFailed = event(UUID.randomUUID().toString(), OutboxEvent.STATUS_FAILED);
        OutboxEvent stuckPending = event(UUID.randomUUID().toString(), OutboxEvent.STATUS_PENDING);

        // 第一批各取 1 条，第二批取空 → 验证循环直到取空
        when(outboxEventMapper.selectExpiredForArchive(eq(OutboxEvent.STATUS_PUBLISHED), any(), eq(200)))
                .thenReturn(List.of(oldPublished)).thenReturn(Collections.emptyList());
        when(outboxEventMapper.selectExpiredForArchive(eq(OutboxEvent.STATUS_FAILED), any(), eq(200)))
                .thenReturn(List.of(oldFailed)).thenReturn(Collections.emptyList());
        when(outboxEventMapper.selectExpiredForArchive(eq(OutboxEvent.STATUS_PENDING), any(), eq(200)))
                .thenReturn(List.of(stuckPending)).thenReturn(Collections.emptyList());

        task.archiveExpiredEvents();

        // PUBLISHED / FAILED：搬入归档表 + 删除原记录（各 1 条）
        verify(outboxEventMapper, times(1)).insertArchive(oldPublished);
        verify(outboxEventMapper, times(1)).deleteByIds(Collections.singletonList(oldPublished.getId()));
        verify(outboxEventMapper, times(1)).insertArchive(oldFailed);
        verify(outboxEventMapper, times(1)).deleteByIds(Collections.singletonList(oldFailed.getId()));

        // 卡死 PENDING：标记 FAILED（不搬入归档；下一轮按 FAILED 规则清理）
        verify(outboxEventMapper).markStuckAsFailed(stuckPending.getId(), "ARCHIVED_AS_STUCK");
        verify(outboxEventMapper, never()).insertArchive(stuckPending);

        // 循环终止：PUBLISHED / FAILED 第二批取空后不再调用；
        // 卡死 PENDING 单批处理（超 200 条的剩余下一轮继续标记）
        verify(outboxEventMapper, times(2))
                .selectExpiredForArchive(eq(OutboxEvent.STATUS_PUBLISHED), any(), eq(200));
        verify(outboxEventMapper, times(2))
                .selectExpiredForArchive(eq(OutboxEvent.STATUS_FAILED), any(), eq(200));
        verify(outboxEventMapper, times(1))
                .selectExpiredForArchive(eq(OutboxEvent.STATUS_PENDING), any(), eq(200));
    }

    @Test
    void doesNotTouchFreshEvents() {
        // 所有批次第一次查询即取空 → 无任何清理动作
        when(outboxEventMapper.selectExpiredForArchive(anyString(), any(), eq(200)))
                .thenReturn(Collections.emptyList());

        task.archiveExpiredEvents();

        verify(outboxEventMapper, never()).insertArchive(any(OutboxEvent.class));
        verify(outboxEventMapper, never()).deleteByIds(anyList());
        verify(outboxEventMapper, never()).markStuckAsFailed(anyString(), anyString());
    }

    @Test
    void archiveOneBatchMovesAllEventsInBatchBeforeDeleting() {
        OutboxEvent a = event(UUID.randomUUID().toString(), OutboxEvent.STATUS_PUBLISHED);
        OutboxEvent b = event(UUID.randomUUID().toString(), OutboxEvent.STATUS_PUBLISHED);
        when(outboxEventMapper.selectExpiredForArchive(eq(OutboxEvent.STATUS_PUBLISHED), any(), eq(200)))
                .thenReturn(List.of(a, b)).thenReturn(Collections.emptyList());

        task.archiveExpiredEvents();

        verify(outboxEventMapper).insertArchive(a);
        verify(outboxEventMapper).insertArchive(b);
        verify(outboxEventMapper).deleteByIds(Collections.singletonList(a.getId()));
        verify(outboxEventMapper).deleteByIds(Collections.singletonList(b.getId()));
    }
}
