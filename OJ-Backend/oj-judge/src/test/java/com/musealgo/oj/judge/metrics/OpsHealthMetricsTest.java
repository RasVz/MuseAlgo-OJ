package com.musealgo.oj.judge.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OpsHealthMetrics} 单测（OPS-02）：SimpleMeterRegistry + 伪造数据源，不连数据库、不启动 Spring。
 *
 * <p>验收点：</p>
 * <ol>
 *   <li>gauge 名与低基数标签符合指标清单；</li>
 *   <li><b>未配置备份监控不得显示健康</b>：{@code oj.backup.configured=0}、{@code oj.backup.healthy=0}、
 *       {@code oj.ops.health.status=0}；</li>
 *   <li>DB 采集失败时降级为 -1 / 健康 0，并累加 {@code oj.monitoring.refresh.failures{stage=db}}；</li>
 *   <li>正常样本下各 gauge 反映快照值。</li>
 * </ol>
 */
class OpsHealthMetricsTest {

    private SimpleMeterRegistry registry;
    private FakeSource source;
    private FakeBackupChecker backup;
    private OpsHealthMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        source = new FakeSource();
        backup = new FakeBackupChecker();
        metrics = new OpsHealthMetrics(registry, source, backup);
        ReflectionTestUtils.setField(metrics, "refreshIntervalMs", 30_000L);
        ReflectionTestUtils.setField(metrics, "judgeStuckTimeoutMs", 600_000L);
        ReflectionTestUtils.setField(metrics, "judgeProcessingSlowSeconds", 120L);
        ReflectionTestUtils.setField(metrics, "judgeProcessingPageSeconds", 300L);
        ReflectionTestUtils.setField(metrics, "judgeFailureWindowMinutes", 60L);
        ReflectionTestUtils.setField(metrics, "judgeSubmissionInflightSlowSeconds", 180L);
        ReflectionTestUtils.setField(metrics, "contestSettlementGraceMinutes", 15L);
        ReflectionTestUtils.setField(metrics, "outboxFailedWindowMinutes", 60L);
        ReflectionTestUtils.setField(metrics, "outboxPendingWarning", 100L);
        ReflectionTestUtils.setField(metrics, "outboxOldestPendingWarningSeconds", 300L);
        ReflectionTestUtils.setField(metrics, "reportWaitWarningSeconds", 1800L);
        ReflectionTestUtils.setField(metrics, "notificationMissingWarningSeconds", 600L);
    }

    private double gauge(String name) {
        var meter = registry.find(name).gauge();
        assertNotNull(meter, "gauge 未注册: " + name);
        return meter.value();
    }

    /** 按成对标签（key1,value1,key2,value2,...）定位 gauge；多标签必须全部匹配。 */
    private double taggedGauge(String name, String... keyValues) {
        var search = registry.find(name);
        for (int i = 0; i < keyValues.length; i += 2) {
            search = search.tag(keyValues[i], keyValues[i + 1]);
        }
        var meter = search.gauge();
        assertNotNull(meter, "gauge 未注册: " + name + " " + List.of(keyValues));
        return meter.value();
    }

    private double counter(String name, String stage) {
        Counter counter = registry.find(name).tag("stage", stage).counter();
        assertNotNull(counter, "counter 未注册: " + name);
        return counter.count();
    }

    @Test
    void allGaugesAreRegisteredWithExpectedNamesAndLowCardinalityTags() {
        String[] names = {
                "oj.judge.stuck", "oj.judge.processing.slow", "oj.judge.processing.oldest.seconds",
                "oj.judge.failed.recent", "oj.judge.failure.reason", "oj.judge.retry.exhausted.recent",
                "oj.judge.retry.pending", "oj.judge.submission.inflight.slow",
                "oj.judge.submission.inflight.oldest.seconds", "oj.contest.unsettled",
                "oj.contest.unsettled.oldest.seconds", "oj.outbox.pending", "oj.outbox.due",
                "oj.outbox.oldest.pending.seconds", "oj.outbox.stuck.pending", "oj.outbox.failed.recent",
                "oj.outbox.backlog.warning", "oj.report.jobs.active", "oj.report.wait.oldest.seconds",
                "oj.report.retry.exhausted", "oj.report.delivery", "oj.notification.report.missing",
                "oj.backup.configured", "oj.backup.healthy", "oj.backup.age.seconds", "oj.backup.corrupt",
                "oj.backup.last.check.ok", "oj.ops.health.status", "oj.monitoring.snapshot.age.seconds",
                "oj.monitoring.stale"};
        for (String name : names) {
            assertNotNull(registry.find(name).gauge(), "gauge 未注册: " + name);
        }
        assertNotNull(registry.find("oj.monitoring.refresh.failures").tag("stage", "db").counter());

        // 低基数标签：report_delivery 只有 status×channel 组合，retry.pending 只有 attempt=1/2
        assertEquals(4 * 4, registry.find("oj.report.delivery").gauges().size());
        assertEquals(2, registry.find("oj.judge.retry.pending").gauges().size());
        assertEquals(2, registry.find("oj.judge.failure.reason").gauges().size());
        assertEquals(3, registry.find("oj.report.jobs.active").gauges().size());
    }

    @Test
    void gaugeBeforeFirstRefreshReturnsMinusOneAndHealthZero() {
        assertEquals(-1.0, gauge("oj.judge.stuck"));
        assertEquals(0.0, gauge("oj.ops.health.status"));
    }

    @Test
    void allClearWithHealthyBackupIsHealthy() {
        backup.status = BackupStatus.ok(120L, true);
        metrics.refresh();
        assertEquals(1.0, gauge("oj.ops.health.status"));
        assertEquals(1.0, gauge("oj.backup.configured"));
        assertEquals(1.0, gauge("oj.backup.healthy"));
        assertEquals(120.0, gauge("oj.backup.age.seconds"));
        assertEquals(0.0, gauge("oj.judge.stuck"));
        assertEquals(0.0, gauge("oj.monitoring.stale"));
    }

    @Test
    void unconfiguredBackupNeverShowsHealthy() {
        backup.status = BackupStatus.notConfigured();
        metrics.refresh();
        assertEquals(0.0, gauge("oj.backup.configured"));
        assertEquals(0.0, gauge("oj.backup.healthy"));
        assertEquals(-1.0, gauge("oj.backup.age.seconds"));
        assertEquals(0.0, gauge("oj.ops.health.status"));
    }

    @Test
    void databaseFailureDegradesToUnknownAndCountsFailure() {
        source.failure = new IllegalStateException("db down");
        metrics.refresh();
        assertEquals(0.0, gauge("oj.ops.health.status"));
        assertEquals(-1.0, gauge("oj.judge.stuck"));
        assertEquals(-1.0, gauge("oj.outbox.pending"));
        assertEquals(1.0, counter("oj.monitoring.refresh.failures", "db"));
        assertFalse(metrics.snapshot().dbOk());
    }

    @Test
    void backupFailureIsCountedAndTreatedAsNotHealthy() {
        backup.failure = new java.io.UncheckedIOException(new java.io.IOException("permission denied"));
        source.values.put("stuck", 0L);
        metrics.refresh();
        assertEquals(1.0, counter("oj.monitoring.refresh.failures", "backup"));
        assertEquals(0.0, gauge("oj.backup.healthy"));
        assertEquals(0.0, gauge("oj.backup.last.check.ok"));
        assertEquals(0.0, gauge("oj.ops.health.status"));
    }

    @Test
    void sampleValuesAreReflectedAndBreachHealth() {
        source.values.put("stuck", 2L);
        source.values.put("failed_recent", 3L);
        source.values.put("retry_exhausted", 1L);
        source.values.put("processing_oldest", 900L);
        source.values.put("unsettled", 4L);
        source.values.put("outbox_failed_recent", 2L);
        source.values.put("report_retry_exhausted", 1L);
        source.values.put("missing", 1L);
        backup.status = BackupStatus.ok(60L, true);
        metrics.refresh();
        assertEquals(2.0, gauge("oj.judge.stuck"));
        assertEquals(3.0, gauge("oj.judge.failed.recent"));
        assertEquals(1.0, gauge("oj.judge.retry.exhausted.recent"));
        assertEquals(900.0, gauge("oj.judge.processing.oldest.seconds"));
        assertEquals(4.0, gauge("oj.contest.unsettled"));
        assertEquals(2.0, gauge("oj.outbox.failed.recent"));
        assertEquals(0.0, gauge("oj.ops.health.status"));
    }

    @Test
    void outboxBacklogRaisesWarningButKeepsHealth() {
        source.values.put("pending", 150L);
        source.values.put("oldest_pending", 600L);
        backup.status = BackupStatus.ok(60L, true);
        metrics.refresh();
        assertEquals(1.0, gauge("oj.outbox.backlog.warning"));
        assertEquals(150.0, taggedGauge("oj.outbox.pending", "status", "PENDING"));
        assertEquals(600.0, gauge("oj.outbox.oldest.pending.seconds"));
        assertEquals(1.0, gauge("oj.ops.health.status"));
    }

    @Test
    void reportDeliveryIsGroupedByStatusAndChannel() {
        source.delivery.add(Map.of("channel", "EMAIL", "status", "FAILED", "cnt", 2L));
        source.delivery.add(Map.of("channel", "IN_APP", "status", "DELIVERED", "cnt", 5L));
        backup.status = BackupStatus.ok(60L, true);
        metrics.refresh();
        // status×channel 是低基数枚举交叉，必须两个标签一起用（只按 status 会命中首个 series）
        assertEquals(2.0, taggedGauge("oj.report.delivery", "status", "FAILED", "channel", "EMAIL"));
        assertEquals(5.0, taggedGauge("oj.report.delivery", "status", "DELIVERED", "channel", "IN_APP"));
        assertEquals(0.0, taggedGauge("oj.report.delivery", "status", "DRY_RUN", "channel", "SMS"));
        // 有 FAILED 交付 → 不健康
        assertEquals(0.0, gauge("oj.ops.health.status"));
    }

    /** 伪造数据源：按 SQL 特征返回可控聚合值；可注入异常模拟 DB 故障。 */
    private static final class FakeSource implements OpsMetricsSource {

        private final Map<String, Long> values = new HashMap<>();
        private final List<Map<String, Object>> delivery = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public Map<String, Object> queryForMap(String sql, Object... args) {
            if (failure != null) {
                throw failure;
            }
            Map<String, Object> row = new HashMap<>();
            if (sql.contains("FROM judge_task")) {
                row.put("stuck", values.getOrDefault("stuck", 0L));
                row.put("processing_slow", values.getOrDefault("processing_slow", 0L));
                row.put("processing_oldest", values.getOrDefault("processing_oldest", 0L));
                row.put("failed_recent", values.getOrDefault("failed_recent", 0L));
                row.put("retry_exhausted", values.getOrDefault("retry_exhausted", 0L));
                row.put("failed_endpoint", values.getOrDefault("failed_endpoint", 0L));
                row.put("failed_other", values.getOrDefault("failed_other", 0L));
                row.put("retry_pending_1", values.getOrDefault("retry_pending_1", 0L));
                row.put("retry_pending_2", values.getOrDefault("retry_pending_2", 0L));
            } else if (sql.contains("FROM submission")) {
                row.put("inflight_slow", values.getOrDefault("inflight_slow", 0L));
                row.put("inflight_oldest", values.getOrDefault("inflight_oldest", 0L));
            } else if (sql.contains("FROM contest")) {
                row.put("unsettled", values.getOrDefault("unsettled", 0L));
                row.put("unsettled_oldest", values.getOrDefault("unsettled_oldest", 0L));
            } else if (sql.contains("FROM outbox_event")) {
                row.put("pending", values.getOrDefault("pending", 0L));
                row.put("due", values.getOrDefault("due", 0L));
                row.put("stuck_pending", values.getOrDefault("stuck_pending", 0L));
                row.put("oldest_pending", values.getOrDefault("oldest_pending", 0L));
                row.put("failed_recent", values.getOrDefault("outbox_failed_recent", 0L));
            } else if (sql.contains("FROM report_job j")) {
                row.put("missing", values.getOrDefault("missing", 0L));
            } else if (sql.contains("FROM report_job")) {
                row.put("pending", values.getOrDefault("report_pending", 0L));
                row.put("generating", values.getOrDefault("report_generating", 0L));
                row.put("failed", values.getOrDefault("report_failed", 0L));
                row.put("wait_oldest", values.getOrDefault("report_wait_oldest", 0L));
                row.put("retry_exhausted", values.getOrDefault("report_retry_exhausted", 0L));
            } else {
                throw new IllegalStateException("未预期的 SQL: " + sql);
            }
            return row;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (failure != null) {
                throw failure;
            }
            return delivery;
        }
    }

    /** 伪造备份检查：默认未配置（最保守），可由用例覆盖。 */
    private static final class FakeBackupChecker extends BackupFreshnessChecker {

        private BackupStatus status = BackupStatus.notConfigured();
        private RuntimeException failure;

        @Override
        public BackupStatus check() {
            if (failure != null) {
                throw failure;
            }
            return status;
        }
    }
}
