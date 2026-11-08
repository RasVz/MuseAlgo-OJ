package com.musealgo.oj.judge.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;

/**
 * 运维健康指标（OPS-02 监控收口）。
 *
 * <p>把「日志里能看见失败」变成可执行运维响应：{@code @Scheduled} 周期性执行只读聚合 SQL 与
 * 备份目录检查，写入 {@link OpsSnapshot}，由 Micrometer gauge 暴露到 {@code /actuator/prometheus}
 * （仅 ADMIN 可见，SecurityConfig 已限定 {@code /actuator/**}）。</p>
 *
 * <h3>指标（Java 名 → Prometheus）</h3>
 * <ul>
 *   <li>判题：{@code oj.judge.stuck} / {@code oj.judge.processing.slow} /
 *       {@code oj.judge.processing.oldest.seconds} / {@code oj.judge.failed.recent} /
 *       {@code oj.judge.failure.reason{reason=endpoint|other}} /
 *       {@code oj.judge.retry.exhausted.recent} / {@code oj.judge.retry.pending{attempt=1|2}} /
 *       {@code oj.judge.submission.inflight.slow} / {@code oj.judge.submission.inflight.oldest.seconds}</li>
 *   <li>结算：{@code oj.contest.unsettled} / {@code oj.contest.unsettled.oldest.seconds}</li>
 *   <li>Outbox：{@code oj.outbox.pending{status=PENDING}} / {@code oj.outbox.due} /
 *       {@code oj.outbox.oldest.pending.seconds} / {@code oj.outbox.stuck.pending} /
 *       {@code oj.outbox.failed.recent}</li>
 *   <li>报告/通知：{@code oj.report.jobs.active{status}} / {@code oj.report.wait.oldest.seconds} /
 *       {@code oj.report.retry.exhausted} / {@code oj.report.delivery{status,channel}} /
 *       {@code oj.notification.report.missing}</li>
 *   <li>备份：{@code oj.backup.configured} / {@code oj.backup.healthy} / {@code oj.backup.age.seconds} /
 *       {@code oj.backup.corrupt} / {@code oj.backup.last.check.ok}</li>
 *   <li>健康与自监控：{@code oj.ops.health.status} / {@code oj.monitoring.snapshot.age.seconds} /
 *       Counter {@code oj.monitoring.refresh.failures{stage=db|backup}}</li>
 * </ul>
 *
 * <p><b>关键语义：未配置备份监控不得显示健康</b> —— 备份未配置时 {@code oj.backup.configured=0}、
 * {@code oj.backup.healthy} 恒 0，且 {@code oj.ops.health.status} 恒 0；永不把「没配」当「健康」。</p>
 *
 * <p><b>标签纪律</b>：只用低基数枚举（status/channel/stage/reason/attempt），
 * 禁止 userId/jobId/submissionId/studentId/classId/姓名/正文/URL/错误原文进入标签；
 * 主键与错误只存在于日志与数据库中。</p>
 *
 * <p><b>外部告警边界</b>：本类不做任何外发（无 SMTP/Webhook/短信）；告警由部署方在 Prometheus/Grafana
 * 基于这些指标配置，或在无面板时使用 {@code python3 scripts/ops.py check} 的 JSON/退出码。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "oj.monitoring", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OpsHealthMetrics {

    /** 判题任务聚合（窗口秒数全部参数化，口径对齐 scripts/ops.py check）。 */
    private static final String JUDGE_SQL = """
            SELECT
                COALESCE(SUM(status = 'PROCESSING' AND locked_at < DATE_SUB(NOW(), INTERVAL ? SECOND)), 0) AS stuck,
                COALESCE(SUM(status = 'PROCESSING' AND locked_at < DATE_SUB(NOW(), INTERVAL ? SECOND)), 0) AS processing_slow,
                COALESCE(MAX(CASE WHEN status = 'PROCESSING' THEN TIMESTAMPDIFF(SECOND, locked_at, NOW()) END), 0) AS processing_oldest,
                COALESCE(SUM(status = 'FAILED' AND updated_at > DATE_SUB(NOW(), INTERVAL ? SECOND)), 0) AS failed_recent,
                COALESCE(SUM(status = 'FAILED' AND attempt_count >= 3
                    AND updated_at > DATE_SUB(NOW(), INTERVAL ? SECOND)), 0) AS retry_exhausted,
                COALESCE(SUM(status = 'FAILED' AND updated_at > DATE_SUB(NOW(), INTERVAL ? SECOND)
                    AND (last_error LIKE '判题机调用失败%' OR last_error LIKE '判题机在途请求已达上限%')), 0) AS failed_endpoint,
                COALESCE(SUM(status = 'FAILED' AND updated_at > DATE_SUB(NOW(), INTERVAL ? SECOND)
                    AND (last_error IS NULL OR (last_error NOT LIKE '判题机调用失败%'
                         AND last_error NOT LIKE '判题机在途请求已达上限%'))), 0) AS failed_other,
                COALESCE(SUM(status = 'PENDING' AND attempt_count = 1), 0) AS retry_pending_1,
                COALESCE(SUM(status = 'PENDING' AND attempt_count = 2), 0) AS retry_pending_2
            FROM judge_task""";

    /** 用户可见的「长时间未出结果」提交（status=0 仍在判题），用于覆盖慢判题+重试退避的长 COMPILING 窗口。 */
    private static final String SUBMISSION_SQL = """
            SELECT
                COALESCE(SUM(status = 0 AND create_time < DATE_SUB(NOW(), INTERVAL ? SECOND)), 0) AS inflight_slow,
                COALESCE(MAX(CASE WHEN status = 0 THEN TIMESTAMPDIFF(SECOND, create_time, NOW()) END), 0) AS inflight_oldest
            FROM submission""";

    private static final String CONTEST_SQL = """
            SELECT
                COALESCE(SUM(1), 0) AS unsettled,
                COALESCE(MAX(TIMESTAMPDIFF(SECOND, end_time, NOW())), 0) AS unsettled_oldest
            FROM contest
            WHERE is_deleted = 0 AND rank_finalized_at IS NULL AND end_time IS NOT NULL
              AND end_time < DATE_SUB(NOW(), INTERVAL ? SECOND)""";

    private static final String OUTBOX_SQL = """
            SELECT
                COALESCE(SUM(status = 'PENDING'), 0) AS pending,
                COALESCE(SUM(status = 'PENDING' AND next_attempt_at <= NOW()), 0) AS due,
                COALESCE(SUM(status = 'PENDING' AND created_at < DATE_SUB(NOW(), INTERVAL 86400 SECOND)), 0) AS stuck_pending,
                COALESCE(MAX(CASE WHEN status = 'PENDING' THEN TIMESTAMPDIFF(SECOND, created_at, NOW()) END), 0) AS oldest_pending,
                COALESCE(SUM(status = 'FAILED' AND created_at > DATE_SUB(NOW(), INTERVAL ? SECOND)), 0) AS failed_recent
            FROM outbox_event""";

    private static final String REPORT_JOB_SQL = """
            SELECT
                COALESCE(SUM(status = 'PENDING'), 0) AS pending,
                COALESCE(SUM(status = 'GENERATING'), 0) AS generating,
                COALESCE(SUM(status = 'FAILED'), 0) AS failed,
                COALESCE(MAX(CASE WHEN status IN ('PENDING', 'GENERATING')
                    THEN TIMESTAMPDIFF(SECOND, create_time, NOW()) END), 0) AS wait_oldest,
                COALESCE(SUM(status = 'FAILED' AND attempts >= 3), 0) AS retry_exhausted
            FROM report_job""";

    private static final String REPORT_DELIVERY_SQL = """
            SELECT channel, status, COUNT(*) AS cnt
            FROM report_delivery
            WHERE create_time > DATE_SUB(NOW(), INTERVAL 24 HOUR)
            GROUP BY channel, status""";

    /**
     * 「应发未发」启发式：GENERATED 超过阈值仍没有 REPLAY_READY 站内通知，且接收者偏好未关闭 reports。
     * user_notification 无投递状态列（V14），该查询是唯一可从 DB 侧观察通知失败的途径。
     */
    private static final String NOTIFICATION_SQL = """
            SELECT COUNT(*) AS missing
            FROM report_job j
            LEFT JOIN `user` u ON u.id = j.requester_id
            WHERE j.status = 'GENERATED'
              AND j.finished_at IS NOT NULL
              AND j.finished_at < DATE_SUB(NOW(), INTERVAL ? SECOND)
              AND NOT EXISTS (
                  SELECT 1 FROM user_notification n
                  WHERE n.target_type = 'REPORT' AND n.target_id = j.id AND n.type = 'REPORT_READY')
              AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(u.preferences, '$.notifications.reports')), 'true') <> 'false'""";

    private static final List<String> REPORT_STATUSES = List.of("PENDING", "GENERATING", "FAILED");
    private static final List<String> DELIVERY_STATUSES = List.of("PENDING", "DELIVERED", "FAILED", "DRY_RUN");
    private static final List<String> DELIVERY_CHANNELS = List.of("IN_APP", "EMAIL", "SMS", "WECHAT");

    /** 自监控失败计数的 stage 取值（低基数）：预热注册，保证启动后抓取即可见 0 而不是缺失。 */
    private static final List<String> REFRESH_STAGES = List.of("db", "backup");

    private final MeterRegistry meterRegistry;
    private final OpsMetricsSource metricsSource;
    private final BackupFreshnessChecker backupFreshnessChecker;

    private final AtomicReference<OpsSnapshot> latest = new AtomicReference<>();
    private final Map<String, Counter> refreshFailures = new HashMap<>();

    @Value("${oj.monitoring.refresh-interval-ms:30000}")
    private long refreshIntervalMs;

    @Value("${oj.monitoring.outbox-pending-warning:100}")
    private long outboxPendingWarning;

    @Value("${oj.monitoring.outbox-oldest-pending-warning-seconds:300}")
    private long outboxOldestPendingWarningSeconds;

    @Value("${oj.judge.watchdog.stuck-timeout-ms:600000}")
    private long judgeStuckTimeoutMs;

    @Value("${oj.monitoring.judge-processing-slow-seconds:120}")
    private long judgeProcessingSlowSeconds;

    @Value("${oj.monitoring.judge-processing-page-seconds:300}")
    private long judgeProcessingPageSeconds;

    @Value("${oj.monitoring.judge-failure-window-minutes:60}")
    private long judgeFailureWindowMinutes;

    @Value("${oj.monitoring.judge-submission-inflight-slow-seconds:180}")
    private long judgeSubmissionInflightSlowSeconds;

    @Value("${oj.monitoring.contest-settlement-grace-minutes:15}")
    private long contestSettlementGraceMinutes;

    @Value("${oj.monitoring.outbox-failed-window-minutes:60}")
    private long outboxFailedWindowMinutes;

    @Value("${oj.monitoring.report-wait-warning-seconds:1800}")
    private long reportWaitWarningSeconds;

    @Value("${oj.monitoring.notification-missing-warning-seconds:600}")
    private long notificationMissingWarningSeconds;

    public OpsHealthMetrics(MeterRegistry meterRegistry,
                            OpsMetricsSource metricsSource,
                            BackupFreshnessChecker backupFreshnessChecker) {
        this.meterRegistry = meterRegistry;
        this.metricsSource = metricsSource;
        this.backupFreshnessChecker = backupFreshnessChecker;
        registerGauges();
    }

    @PostConstruct
    void init() {
        refresh();
    }

    /** 周期性刷新快照；任何异常都降级为 {@link OpsSnapshot#unknown} 并计入失败计数，绝不抛给调度器。 */
    @Scheduled(fixedDelayString = "${oj.monitoring.refresh-interval-ms:30000}")
    public synchronized void refresh() {
        long capturedAt = Instant.now().getEpochSecond();
        BackupStatus backup;
        try {
            backup = backupFreshnessChecker.check();
        } catch (Exception e) {
            refreshFailure("backup").increment();
            log.warn("【监控】备份状态检查异常 stage=backup: {}", e.getMessage());
            backup = BackupStatus.checkFailed();
        }
        OpsSnapshot snapshot;
        try {
            snapshot = loadSnapshot(capturedAt, backup);
        } catch (Exception e) {
            refreshFailure("db").increment();
            log.warn("【监控】指标快照刷新失败 stage=db: {}", e.getMessage());
            snapshot = OpsSnapshot.unknown(backup, capturedAt);
        }
        latest.set(snapshot);
        if (log.isDebugEnabled()) {
            log.debug("【监控】指标快照刷新完成 health={}, capturedAt={}",
                    snapshot.healthStatus(reportWaitWarningSeconds, judgeProcessingPageSeconds), capturedAt);
        }
    }

    /** 当前快照（可能为 null：首次刷新尚未完成）。 */
    public OpsSnapshot snapshot() {
        return latest.get();
    }

    private OpsSnapshot loadSnapshot(long capturedAt, BackupStatus backup) {
        Map<String, Object> judge = metricsSource.queryForMap(JUDGE_SQL,
                seconds(judgeStuckTimeoutMs),
                judgeProcessingSlowSeconds,
                minutes(judgeFailureWindowMinutes),
                minutes(judgeFailureWindowMinutes),
                minutes(judgeFailureWindowMinutes),
                minutes(judgeFailureWindowMinutes));

        Map<String, Object> submission = metricsSource.queryForMap(SUBMISSION_SQL,
                judgeSubmissionInflightSlowSeconds);

        Map<String, Object> contest = metricsSource.queryForMap(CONTEST_SQL,
                minutes(contestSettlementGraceMinutes));

        Map<String, Object> outbox = metricsSource.queryForMap(OUTBOX_SQL,
                minutes(outboxFailedWindowMinutes));

        Map<String, Object> report = metricsSource.queryForMap(REPORT_JOB_SQL);

        Map<String, Long> delivery = new HashMap<>();
        for (String status : DELIVERY_STATUSES) {
            for (String channel : DELIVERY_CHANNELS) {
                delivery.put(status + "/" + channel, 0L);
            }
        }
        for (Map<String, Object> row : metricsSource.queryForList(REPORT_DELIVERY_SQL)) {
            String status = String.valueOf(row.get("status"));
            String channel = String.valueOf(row.get("channel"));
            if (DELIVERY_STATUSES.contains(status) && DELIVERY_CHANNELS.contains(channel)) {
                delivery.put(status + "/" + channel, asLong(row.get("cnt")));
            }
        }

        Map<String, Object> notification = metricsSource.queryForMap(NOTIFICATION_SQL,
                notificationMissingWarningSeconds);

        return new OpsSnapshot(
                asLong(judge.get("stuck")),
                asLong(judge.get("processing_slow")),
                asLong(judge.get("processing_oldest")),
                asLong(judge.get("failed_recent")),
                asLong(judge.get("failed_endpoint")),
                asLong(judge.get("retry_exhausted")),
                asLong(judge.get("retry_pending_1")),
                asLong(judge.get("retry_pending_2")),
                asLong(submission.get("inflight_slow")),
                asLong(submission.get("inflight_oldest")),
                asLong(contest.get("unsettled")),
                asLong(contest.get("unsettled_oldest")),
                asLong(outbox.get("pending")),
                asLong(outbox.get("due")),
                asLong(outbox.get("stuck_pending")),
                asLong(outbox.get("oldest_pending")),
                asLong(outbox.get("failed_recent")),
                asLong(report.get("pending")),
                asLong(report.get("generating")),
                asLong(report.get("failed")),
                asLong(report.get("wait_oldest")),
                asLong(report.get("retry_exhausted")),
                Map.copyOf(delivery),
                asLong(notification.get("missing")),
                backup,
                true,
                capturedAt);
    }

    private void registerGauges() {
        gauge("oj.judge.stuck", s -> s.judgeStuck());
        gauge("oj.judge.processing.slow", s -> s.judgeProcessingSlow());
        gauge("oj.judge.processing.oldest.seconds", s -> s.judgeProcessingOldestSeconds());
        gauge("oj.judge.failed.recent", s -> s.judgeFailedRecent());
        gauge("oj.judge.failure.reason", Tags.of("reason", "endpoint"), s -> s.judgeFailedEndpointRecent());
        gauge("oj.judge.failure.reason", Tags.of("reason", "other"),
                s -> s.judgeFailedRecent() - s.judgeFailedEndpointRecent());
        gauge("oj.judge.retry.exhausted.recent", s -> s.judgeRetryExhaustedRecent());
        gauge("oj.judge.retry.pending", Tags.of("attempt", "1"), s -> s.judgeRetryPendingAttempt1());
        gauge("oj.judge.retry.pending", Tags.of("attempt", "2"), s -> s.judgeRetryPendingAttempt2());
        gauge("oj.judge.submission.inflight.slow", s -> s.judgeSubmissionInflightSlow());
        gauge("oj.judge.submission.inflight.oldest.seconds", s -> s.judgeSubmissionInflightOldestSeconds());
        gauge("oj.contest.unsettled", s -> s.contestUnsettled());
        gauge("oj.contest.unsettled.oldest.seconds", s -> s.contestUnsettledOldestSeconds());
        gauge("oj.outbox.pending", Tags.of("status", "PENDING"), s -> s.outboxPending());
        gauge("oj.outbox.due", s -> s.outboxDue());
        gauge("oj.outbox.oldest.pending.seconds", s -> s.outboxOldestPendingSeconds());
        gauge("oj.outbox.stuck.pending", s -> s.outboxStuckPending());
        gauge("oj.outbox.failed.recent", s -> s.outboxFailedRecent());
        for (String status : REPORT_STATUSES) {
            gauge("oj.report.jobs.active", Tags.of("status", status), s -> switch (status) {
                case "PENDING" -> s.reportPending();
                case "GENERATING" -> s.reportGenerating();
                default -> s.reportFailed();
            });
        }
        gauge("oj.report.wait.oldest.seconds", s -> s.reportWaitOldestSeconds());
        gauge("oj.report.retry.exhausted", s -> s.reportRetryExhausted());
        for (String status : DELIVERY_STATUSES) {
            for (String channel : DELIVERY_CHANNELS) {
                gauge("oj.report.delivery",
                        Tags.of("status", status, "channel", channel),
                        s -> s.deliveryCount(status, channel));
            }
        }
        gauge("oj.notification.report.missing", s -> s.notificationReportMissing());
        gauge("oj.backup.configured", s -> s.backup() != null && s.backup().configured() ? 1 : 0);
        gauge("oj.backup.healthy", s -> s.backup() != null && s.backup().healthy() ? 1 : 0);
        gauge("oj.backup.age.seconds", s -> s.backup() == null ? -1 : s.backup().ageSeconds());
        gauge("oj.backup.corrupt", s -> s.backup() != null && s.backup().corrupt() ? 1 : 0);
        gauge("oj.backup.last.check.ok", s -> s.backup() != null && s.backup().lastCheckOk() ? 1 : 0);
        // 积压类「警告」不折叠进健康位（避免长期告警疲劳），单独暴露 0/1 供面板使用
        gauge("oj.outbox.backlog.warning",
                s -> s.outboxPending() >= outboxPendingWarning
                        || s.outboxOldestPendingSeconds() >= outboxOldestPendingWarningSeconds ? 1 : 0);
        // >2×刷新周期说明快照刷新停摆（调度线程饿死/DB 卡住）
        gauge("oj.monitoring.stale",
                s -> Instant.now().getEpochSecond() - s.capturedAtEpochSeconds() > 2 * Math.max(1L, refreshIntervalMs / 1000L)
                        ? 1 : 0);
        healthGauge("oj.ops.health.status",
                s -> s.healthStatus(reportWaitWarningSeconds, judgeProcessingPageSeconds));
        gauge("oj.monitoring.snapshot.age.seconds",
                s -> Math.max(0L, Instant.now().getEpochSecond() - s.capturedAtEpochSeconds()));
        // 自监控计数器预热：即使从未失败，抓取时也应看到 oj_monitoring_refresh_failures{stage=db|backup} 0
        for (String stage : REFRESH_STAGES) {
            refreshFailure(stage);
        }
    }

    /** 注册读取 {@link #latest} 的 gauge；空快照返回 -1（健康位由 healthStatus 保证 0）。 */
    private void gauge(String name, ToDoubleFunction<OpsSnapshot> value) {
        registerGauge(name, value);
    }

    private void gauge(String name, Tags tags, ToDoubleFunction<OpsSnapshot> value) {
        registerGauge(name, tags, value);
    }

    private void registerGauge(String name, ToDoubleFunction<OpsSnapshot> value) {
        meterRegistry.gauge(name, latest, ref -> {
            OpsSnapshot snapshot = ref.get();
            return snapshot == null ? -1D : value.applyAsDouble(snapshot);
        });
    }

    private void registerGauge(String name, Tags tags, ToDoubleFunction<OpsSnapshot> value) {
        meterRegistry.gauge(name, tags, latest, ref -> {
            OpsSnapshot snapshot = ref.get();
            return snapshot == null ? -1D : value.applyAsDouble(snapshot);
        });
    }

    /**
     * 注册健康位 gauge：<b>首次刷新尚未完成时返回 0（不健康）而不是 -1</b>，
     * 保证 {@code oj_ops_health_status} 的语义始终是 0/1，抓取方不会把 -1 误读成「未知即健康」。
     */
    private void healthGauge(String name, ToDoubleFunction<OpsSnapshot> value) {
        meterRegistry.gauge(name, latest, ref -> {
            OpsSnapshot snapshot = ref.get();
            return snapshot == null ? 0D : value.applyAsDouble(snapshot);
        });
    }

    private Counter refreshFailure(String stage) {
        return refreshFailures.computeIfAbsent(stage,
                key -> Counter.builder("oj.monitoring.refresh.failures")
                        .tag("stage", key)
                        .register(meterRegistry));
    }

    private static long seconds(long millis) {
        return Math.max(1L, millis / 1000L);
    }

    private static long minutes(long values) {
        return Math.max(1L, values * 60L);
    }

    private static long asLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.longValue();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
