package com.musealgo.oj.judge.metrics;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link OpsSnapshot} 健康判定单测（OPS-02）。
 *
 * <p>核心验收点：<b>未配置备份监控不得显示健康</b> —— 无论其他指标多干净，
 * 只要备份未配置/损坏/过期，{@code healthStatus} 必为 0。</p>
 */
class OpsSnapshotTest {

    private static final long REPORT_WAIT = 1800L;
    private static final long JUDGE_PAGE = 300L;
    private static final BackupStatus OK = BackupStatus.ok(60L, true);

    /**
     * 构造快照：只暴露故障类字段便于逐项验证，其余为 0（healthy baseline）。
     */
    private static OpsSnapshot snapshot(BackupStatus backup,
                                        long judgeStuck,
                                        long judgeProcessingOldest,
                                        long judgeFailedRecent,
                                        long judgeRetryExhausted,
                                        long contestUnsettled,
                                        long outboxFailedRecent,
                                        long outboxStuckPending,
                                        long reportRetryExhausted,
                                        long notificationMissing,
                                        Map<String, Long> delivery,
                                        long reportWaitOldest) {
        return new OpsSnapshot(judgeStuck, 0, judgeProcessingOldest, judgeFailedRecent, 0,
                judgeRetryExhausted, 0, 0, 0, 0,
                contestUnsettled, 0,
                0, 0, outboxStuckPending, 0, outboxFailedRecent,
                0, 0, 0, reportWaitOldest, reportRetryExhausted,
                delivery, notificationMissing, backup, true, 1_000L);
    }

    private static OpsSnapshot clean(BackupStatus backup) {
        return snapshot(backup, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of(), 0);
    }

    @Test
    void allClearWithHealthyBackupIsHealthy() {
        assertEquals(1, clean(OK).healthStatus(REPORT_WAIT, JUDGE_PAGE));
    }

    @Test
    void unconfiguredBackupIsNeverHealthy() {
        OpsSnapshot snapshot = clean(BackupStatus.notConfigured());
        assertEquals(0, snapshot.backup().configured() ? 1 : 0);
        assertEquals(0, snapshot.backup().healthy() ? 1 : 0);
        assertEquals(-1, snapshot.backup().ageSeconds());
        assertEquals(0, snapshot.healthStatus(REPORT_WAIT, JUDGE_PAGE));
    }

    @Test
    void missingCorruptStaleOrFailedCheckIsNotHealthy() {
        assertEquals(0, clean(BackupStatus.missing()).healthStatus(REPORT_WAIT, JUDGE_PAGE));
        assertEquals(0, clean(BackupStatus.corrupted()).healthStatus(REPORT_WAIT, JUDGE_PAGE));
        assertEquals(0, clean(BackupStatus.checkFailed()).healthStatus(REPORT_WAIT, JUDGE_PAGE));
        assertEquals(0, clean(BackupStatus.ok(27 * 3600L, false)).healthStatus(REPORT_WAIT, JUDGE_PAGE));
    }

    @Test
    void databaseFailureIsNeverHealthy() {
        OpsSnapshot snapshot = OpsSnapshot.unknown(OK, 1_000L);
        assertEquals(0, snapshot.dbOk() ? 1 : 0);
        assertEquals(-1, snapshot.judgeStuck());
        assertEquals(0, snapshot.healthStatus(REPORT_WAIT, JUDGE_PAGE));
    }

    @Test
    void criticalFailureSignalsFlipHealthToZero() {
        assertEquals(0, snapshot(OK, 1, 0, 0, 0, 0, 0, 0, 0, 0, Map.of(), 0)
                .healthStatus(REPORT_WAIT, JUDGE_PAGE));
        assertEquals(0, snapshot(OK, 0, 0, 1, 0, 0, 0, 0, 0, 0, Map.of(), 0)
                .healthStatus(REPORT_WAIT, JUDGE_PAGE));
        assertEquals(0, snapshot(OK, 0, 0, 0, 1, 0, 0, 0, 0, 0, Map.of(), 0)
                .healthStatus(REPORT_WAIT, JUDGE_PAGE));
        assertEquals(0, snapshot(OK, 0, JUDGE_PAGE + 1, 0, 0, 0, 0, 0, 0, 0, Map.of(), 0)
                .healthStatus(REPORT_WAIT, JUDGE_PAGE));
        assertEquals(0, snapshot(OK, 0, 0, 0, 0, 1, 0, 0, 0, 0, Map.of(), 0)
                .healthStatus(REPORT_WAIT, JUDGE_PAGE));
        assertEquals(0, snapshot(OK, 0, 0, 0, 0, 0, 1, 0, 0, 0, Map.of(), 0)
                .healthStatus(REPORT_WAIT, JUDGE_PAGE));
        assertEquals(0, snapshot(OK, 0, 0, 0, 0, 0, 0, 1, 0, 0, Map.of(), 0)
                .healthStatus(REPORT_WAIT, JUDGE_PAGE));
        assertEquals(0, snapshot(OK, 0, 0, 0, 0, 0, 0, 0, 1, 0, Map.of(), 0)
                .healthStatus(REPORT_WAIT, JUDGE_PAGE));
        assertEquals(0, snapshot(OK, 0, 0, 0, 0, 0, 0, 0, 0, 1, Map.of(), 0)
                .healthStatus(REPORT_WAIT, JUDGE_PAGE));
        assertEquals(0, snapshot(OK, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of("FAILED/EMAIL", 1L), 0)
                .healthStatus(REPORT_WAIT, JUDGE_PAGE));
        assertEquals(0, snapshot(OK, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of(), REPORT_WAIT + 1)
                .healthStatus(REPORT_WAIT, JUDGE_PAGE));
    }

    @Test
    void warnOnlyBacklogKeepsHealthButIsVisible() {
        OpsSnapshot backlog = snapshot(OK, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of(), 0);
        assertEquals(1, backlog.healthStatus(REPORT_WAIT, JUDGE_PAGE));
        assertEquals(0, backlog.outboxPending());
    }

    @Test
    void deliveryAggregatesByLowCardinalityKeys() {
        Map<String, Long> delivery = Map.of(
                "FAILED/EMAIL", 2L,
                "FAILED/WECHAT", 3L,
                "DELIVERED/IN_APP", 5L);
        OpsSnapshot snapshot = snapshot(OK, 0, 0, 0, 0, 0, 0, 0, 0, 0, delivery, 0);
        assertEquals(2, snapshot.deliveryCount("FAILED", "EMAIL"));
        assertEquals(0, snapshot.deliveryCount("PENDING", "SMS"));
        assertEquals(5, snapshot.deliveryFailed());
    }
}
