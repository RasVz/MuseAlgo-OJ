package com.musealgo.oj.judge.metrics;

import java.util.Map;

/**
 * 运维指标快照（OPS-02）：一次刷新周期内所有 {@code oj.*} 指标的只读值。
 *
 * <p>不可变对象；由 {@link OpsHealthMetrics} 在 {@code @Scheduled} 刷新时整体替换，
 * gauge 读取快照而不是每次抓取都查库（避免 Prometheus 抓取把压力打到 DB）。</p>
 *
 * <p>约定：数据库整体不可用 / 快照未完成时所有 DB 派生值为 {@code -1}，
 * {@code dbOk=false}；此时 {@link #healthStatus(long, long)} 必为 0（不健康），
 * 绝不让「采集失败」被误读为「一切正常」。</p>
 *
 * <p>标签纪律：本快照只保存按枚举聚合后的计数（status/channel），
 * 不含 userId/jobId/studentId/姓名/正文/URL/错误原文。</p>
 */
public record OpsSnapshot(
        long judgeStuck,
        long judgeProcessingSlow,
        long judgeProcessingOldestSeconds,
        long judgeFailedRecent,
        long judgeFailedEndpointRecent,
        long judgeRetryExhaustedRecent,
        long judgeRetryPendingAttempt1,
        long judgeRetryPendingAttempt2,
        long judgeSubmissionInflightSlow,
        long judgeSubmissionInflightOldestSeconds,
        long contestUnsettled,
        long contestUnsettledOldestSeconds,
        long outboxPending,
        long outboxDue,
        long outboxStuckPending,
        long outboxOldestPendingSeconds,
        long outboxFailedRecent,
        long reportPending,
        long reportGenerating,
        long reportFailed,
        long reportWaitOldestSeconds,
        long reportRetryExhausted,
        Map<String, Long> reportDelivery,
        long notificationReportMissing,
        BackupStatus backup,
        boolean dbOk,
        long capturedAtEpochSeconds) {

    /** 采集失败 / 未完成：DB 派生值全 -1，dbOk=false，健康位必为 0。 */
    public static OpsSnapshot unknown(BackupStatus backup, long capturedAtEpochSeconds) {
        return new OpsSnapshot(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, Map.of(), -1,
                backup == null ? BackupStatus.notConfigured() : backup, false, capturedAtEpochSeconds);
    }

    /** 指定 status/channel 的交付计数（缺省 0）。 */
    public long deliveryCount(String status, String channel) {
        Long value = reportDelivery.get(status + "/" + channel);
        return value == null ? 0L : value;
    }

    /** 24h 窗口内 FAILED 的交付总数（任何渠道）。 */
    public long deliveryFailed() {
        long total = 0L;
        for (Map.Entry<String, Long> entry : reportDelivery.entrySet()) {
            if (entry.getKey().startsWith("FAILED/")) {
                total += entry.getValue();
            }
        }
        return total;
    }

    /**
     * 统一健康出口 {@code oj_ops_health_status}：1=全部关键项通过；0=任一关键项故障或备份监控未配置。
     *
     * <p>只把「需要人工介入/不可自愈」的信号折叠进健康位：卡死、失败、重试耗尽、未结算、
     * Outbox 失败/卡死、报告重试耗尽、应发未发通知、交付失败、报告等待超阈值、判题 PROCESSING 超分页阈值、
     * 以及<b>备份未配置/损坏/过期</b>。纯警告类（队列深度、积压、DRY_RUN 演示、进行中的正常排队）
     * 只暴露指标不折叠，避免健康位长期告警疲劳。</p>
     *
     * @param reportWaitWarningSeconds    报告等待告警阈值（默认 1800s）
     * @param judgeProcessingPageSeconds  判题 PROCESSING 分页阈值（默认 300s；看门狗 600s 才回收）
     */
    public int healthStatus(long reportWaitWarningSeconds, long judgeProcessingPageSeconds) {
        if (!dbOk) {
            return 0;
        }
        if (backup == null || !backup.configured() || !backup.healthy() || backup.corrupt()) {
            // 未配置备份监控不得显示健康（OPS-02 关键语义）。
            return 0;
        }
        if (judgeStuck > 0
                || judgeFailedRecent > 0
                || judgeRetryExhaustedRecent > 0
                || judgeProcessingOldestSeconds > judgeProcessingPageSeconds
                || contestUnsettled > 0
                || outboxFailedRecent > 0
                || outboxStuckPending > 0
                || reportRetryExhausted > 0
                || notificationReportMissing > 0
                || deliveryFailed() > 0
                || reportWaitOldestSeconds > reportWaitWarningSeconds) {
            return 0;
        }
        return 1;
    }
}
