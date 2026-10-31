package com.musealgo.oj.analytics.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import com.musealgo.oj.analytics.application.TagRatingService;
import com.musealgo.oj.analytics.mapper.ProfileSnapshotMapper;
import com.musealgo.oj.api.judge.RejudgePostProcessEvent;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 重判后处理监听器单测（T8）：ELO 重建 + 快照标脏 + 两段失败隔离。
 * <p>
 * seam：监听器公开入口 {@code onRejudgePostProcess} 对两个协作者的可观察调用。
 * AFTER_COMMIT 语义由 Spring 事务事件机制保证，这里只验证业务编排与异常吞掉行为。
 */
@ExtendWith(MockitoExtension.class)
class RejudgePostProcessListenerTest {

    @Mock
    private TagRatingService tagRatingService;
    @Mock
    private ProfileSnapshotMapper profileSnapshotMapper;
    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private RejudgePostProcessListener listener;

    @BeforeEach
    void setUp() {
        // 空事务即可：TransactionTemplate 只负责「另起 REQUIRES_NEW」的调用编排，
        // 单测里没有真实连接，getTransaction 返回 null 就能走完回调（同 OutboxArchiveTaskTest）。
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(null);
    }

    /** 2026-10-14 是周三，所在统计周的周一是 2026-10-12。 */
    private static final LocalDateTime WEDNESDAY = LocalDateTime.of(2026, 10, 14, 10, 30);
    private static final LocalDate WEEK_START = LocalDate.of(2026, 10, 12);

    private static RejudgePostProcessEvent event(LocalDateTime submissionTime) {
        return new RejudgePostProcessEvent(1L, 2L, 3L, 4L, 5L, submissionTime, null);
    }

    @Test
    void rebuildsEloAndMarksSnapshotOfSubmissionWeekStale() {
        listener.onRejudgePostProcess(event(WEDNESDAY));

        // 顺序也是契约：先重建 ELO，再标脏（ELO 结果与快照周期互不依赖，但保持任务约定顺序）
        InOrder order = inOrder(tagRatingService, profileSnapshotMapper);
        order.verify(tagRatingService).rebuildForUser(4L);
        order.verify(profileSnapshotMapper).markStaleForPeriod(4L, WEEK_START);
    }

    @Test
    void eloFailureDoesNotPreventSnapshotMarkingAndIsSwallowed() {
        doThrow(new IllegalStateException("tag rating rebuild boom"))
                .when(tagRatingService).rebuildForUser(4L);

        assertDoesNotThrow(() -> listener.onRejudgePostProcess(event(WEDNESDAY)));

        // 第一段失败不能阻塞第二段
        verify(profileSnapshotMapper).markStaleForPeriod(4L, WEEK_START);
    }

    @Test
    void snapshotMarkFailureIsSwallowedAndDoesNotFailRejudge() {
        doThrow(new IllegalStateException("mark stale boom"))
                .when(profileSnapshotMapper).markStaleForPeriod(anyLong(), any());

        assertDoesNotThrow(() -> listener.onRejudgePostProcess(event(WEDNESDAY)));

        verify(tagRatingService).rebuildForUser(4L);
    }

    @Test
    void missingSubmissionTimeSkipsSnapshotButStillRebuildsElo() {
        listener.onRejudgePostProcess(event(null));

        verify(tagRatingService).rebuildForUser(4L);
        verifyNoInteractions(profileSnapshotMapper);
    }

    @Test
    void missingUserIdSkipsSnapshotButStillRebuildsElo() {
        RejudgePostProcessEvent noUser = new RejudgePostProcessEvent(
                1L, 2L, 3L, null, 5L, WEDNESDAY, null);

        listener.onRejudgePostProcess(noUser);

        verify(tagRatingService).rebuildForUser(null);
        verifyNoInteractions(profileSnapshotMapper);
    }

    @Test
    void nullEventIsIgnored() {
        assertDoesNotThrow(() -> listener.onRejudgePostProcess(null));

        verifyNoInteractions(tagRatingService);
        verifyNoInteractions(profileSnapshotMapper);
    }

    @Test
    void weekStartOfUsesPreviousOrSameMonday() {
        // 周一 → 当天；周日 → 前一周周一（跨周边界）
        assertEquals(LocalDate.of(2026, 10, 12), RejudgePostProcessListener.weekStartOf(
                LocalDate.of(2026, 10, 12)));
        assertEquals(LocalDate.of(2026, 10, 12), RejudgePostProcessListener.weekStartOf(
                LocalDate.of(2026, 10, 18)));
        assertEquals(LocalDate.of(2026, 10, 12), RejudgePostProcessListener.weekStartOf(
                LocalDate.of(2026, 10, 17)));
    }
}
