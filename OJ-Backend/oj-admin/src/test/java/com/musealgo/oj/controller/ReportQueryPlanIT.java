package com.musealgo.oj.controller;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.musealgo.oj.analytics.entity.ReportJob;
import com.musealgo.oj.analytics.mapper.ReportJobMapper;
import com.musealgo.oj.community.notification.ReportNotificationDispatchRepository;
import com.musealgo.oj.community.notification.ReportNotificationAdminService;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL acceptance: plans, preserved ordering and controlled concurrent locks. */
@SpringBootTest(properties = {"oj.messaging.rabbitmq-enabled=false", "oj.judge.worker.enabled=false",
        "report.worker.enabled=false", "report.notification.worker-enabled=false",
        "report.weekly-enabled=false", "report.retention-cleanup-enabled=false"})
class ReportQueryPlanIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ReportJobMapper mapper;
    @Autowired ReportNotificationDispatchRepository notifications;
    @Autowired ReportNotificationAdminService adminQueue;
    @Autowired SqlSessionFactory sessions;
    @Autowired PlatformTransactionManager transactionManager;
    private TransactionTemplate transactions;
    private long classId;
    private long nextStudent;

    @BeforeEach void setup() {
        transactions = new TransactionTemplate(transactionManager);
        classId = 2_000_000_000L + Math.floorMod(System.nanoTime(), 500_000_000L);
        nextStudent = classId;
    }

    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM report_notification_intent WHERE job_id IN (SELECT id FROM report_job WHERE class_id=?)", classId);
        jdbc.update("DELETE FROM report_job WHERE class_id = ?", classId);
    }

    private long insert(String status, String created, String next, String lease) {
        long student = nextStudent++;
        jdbc.update("""
                INSERT INTO report_job(student_id,class_id,period_start,period_end,status,create_time,
                                       next_attempt_at,lease_until,lease_owner)
                VALUES (?,?,'2026-01-05','2026-01-11',?,?,?,?,?)
                """, student, classId, status, created, next, lease,
                "GENERATING".equals(status) ? "synthetic-owner" : null);
        return jdbc.queryForObject("SELECT id FROM report_job WHERE class_id=? AND student_id=?", Long.class, classId, student);
    }

    @Test void claimKeepsExpiredPriorityAndOriginalWithinStatusOrdering() {
        long older = insert("GENERATING", "2020-01-01 00:00:00", null, "2020-02-01 00:00:00");
        long later = insert("GENERATING", "2020-01-02 00:00:00", null, "2020-01-01 00:00:00");
        long immediate = insert("PENDING", "2020-01-05 00:00:00", null, null);
        long due = insert("PENDING", "2019-01-01 00:00:00", "2020-01-04 00:00:00", null);
        assertEquals(older, transactions.execute(tx -> mapper.selectNextDueForUpdate()).getId());
        jdbc.update("UPDATE report_job SET status='FAILED',lease_owner=NULL,lease_until=NULL WHERE id=?", older);
        assertEquals(later, transactions.execute(tx -> mapper.selectNextDueForUpdate()).getId());
        jdbc.update("UPDATE report_job SET status='FAILED',lease_owner=NULL,lease_until=NULL WHERE id=?", later);
        assertEquals(due, transactions.execute(tx -> mapper.selectNextDueForUpdate()).getId());
        jdbc.update("UPDATE report_job SET status='FAILED',next_attempt_at=NULL WHERE id=?", due);
        assertEquals(immediate, transactions.execute(tx -> mapper.selectNextDueForUpdate()).getId());
    }

    @Test void twoWorkersSkipLockedRowsAndConcurrentEnqueueDoesNotBlock() throws Exception {
        for (int i=0;i<60;i++) insert("PENDING", "2020-01-01 00:00:00", null, null);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch held = new CountDownLatch(2), release = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Long> claim = () -> transactions.execute(tx -> {
                ReportJob job = mapper.selectNextDueForUpdate();
                held.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("lock release timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return job == null ? null : job.getId();
            });
            Future<Long> first = pool.submit(claim), second = pool.submit(claim);
            assertTrue(held.await(8, TimeUnit.SECONDS), "both claim transactions must reach their locked result");
            Future<Long> enqueue = pool.submit(() -> insert("PENDING", "2020-01-02 00:00:00", null, null));
            assertNotNull(enqueue.get(5, TimeUnit.SECONDS), "concurrent enqueue must not wait behind a scan of all due rows");
            release.countDown();
            Long a = first.get(10, TimeUnit.SECONDS), b = second.get(10, TimeUnit.SECONDS);
            assertNotNull(a); assertNotNull(b); assertNotEquals(a,b);
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS)); }
    }

    @Test void candidatePaginationSkipsMoreThanOnePageOfLockedJobs() throws Exception {
        for (int i=0;i<101;i++) insert("PENDING", "2020-01-01 00:00:00", null, null);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            Future<?> holder = pool.submit(() -> transactions.executeWithoutResult(tx -> {
                List<Long> ids = jdbc.queryForList("SELECT id FROM report_job WHERE class_id=? ORDER BY id LIMIT 100", Long.class, classId);
                for (Long id : ids) jdbc.queryForObject("SELECT id FROM report_job WHERE id=? FOR UPDATE", Long.class, id);
                locked.countDown();
                try { if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("lock release timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            Long last = jdbc.queryForObject("SELECT MAX(id) FROM report_job WHERE class_id=?", Long.class, classId);
            assertEquals(last, transactions.execute(tx -> mapper.selectNextDueForUpdate()).getId());
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS)); }
    }

    @Test void retentionKeepsMonthEndDateAddSemantics() {
        long leapDay = insert("GENERATED", "2024-02-29 12:00:00", null, null);
        long march = insert("GENERATED", "2024-03-01 12:00:00", null, null);
        transactions.executeWithoutResult(tx -> {
            jdbc.execute("SET timestamp = UNIX_TIMESTAMP('2025-02-28 12:00:00')");
            try {
                List<Long> ids = mapper.selectExpiredTerminalForUpdate(12, 500).stream().map(ReportJob::getId).toList();
                assertTrue(ids.contains(leapDay));
                assertFalse(ids.contains(march));
            } finally { jdbc.execute("SET timestamp = 0"); }
        });
    }

    @Test void notificationDiscoveryOrdersDueWorkAndExcludesFutureWork() {
        long job = insert("GENERATED", "2020-01-01 00:00:00", null, null);
        long later = insertIntent(job, "later", "PENDING", "2020-01-05 00:00:00");
        long expired = insertIntent(job, "expired", "DISPATCHING", "2020-01-02 00:00:00");
        long earlier = insertIntent(job, "earlier", "PENDING", "2020-01-01 00:00:00");
        long immediate = insertIntent(job, "immediate", "PENDING", null);
        long future = insertIntent(job, "future", "PENDING", "2999-01-01 00:00:00");
        List<Long> own = notifications.findDueIntentIds(100).stream()
                .filter(id -> List.of(later, expired, earlier, immediate, future).contains(id)).toList();
        assertEquals(List.of(immediate, earlier, expired, later), own);
    }

    @Test void adminQueuePreservesGlobalRecencyAndStatusFilterUsesItsIndex() {
        long job = insert("GENERATED", "2020-01-01 00:00:00", null, null);
        long oldest = insertIntent(job, "admin-oldest", "PENDING", null);
        long failed = insertIntent(job, "admin-failed", "PENDING", null);
        long tied = insertIntent(job, "admin-tied", "PENDING", null);
        long newest = insertIntent(job, "admin-newest", "PENDING", null);
        jdbc.update("UPDATE report_notification_intent SET status='FAILED' WHERE id IN (?,?)", failed, newest);
        jdbc.update("UPDATE report_notification_intent SET updated_at='2020-01-01 00:00:00' WHERE id=?", oldest);
        jdbc.update("UPDATE report_notification_intent SET updated_at='2020-01-02 00:00:00' WHERE id=?", failed);
        jdbc.update("UPDATE report_notification_intent SET updated_at='2020-01-03 00:00:00' WHERE id IN (?,?)", tied, newest);

        assertEquals(List.of(newest, tied), adminQueue.list(null, null, job, 1, 2).items().stream()
                .map(item -> item.intentId()).toList());
        assertEquals(List.of(failed, oldest), adminQueue.list(null, null, job, 2, 2).items().stream()
                .map(item -> item.intentId()).toList());
        assertEquals(List.of(newest, failed), adminQueue.list("FAILED", null, job, 1, 10).items().stream()
                .map(item -> item.intentId()).toList());

        addPlanFixtures(job);
        Map<String, Object> plan = jdbc.queryForMap("""
                EXPLAIN SELECT id, event_kind, job_id, recipient_id, generation_no, terminal_revision,
                    source_error_code, status, attempts, max_attempts, redrive_round, next_attempt_at,
                    last_error_code, notification_id, redrive_admin_id, redriven_at, redrive_reason,
                    created_at, updated_at FROM report_notification_intent
                WHERE status=? ORDER BY status DESC, updated_at DESC, id DESC LIMIT ? OFFSET ?
                """, "DISPATCHING", 20, 0);
        System.out.println("ADMIN_QUEUE_STATUS_EXPLAIN " + plan);
        assertEquals("ix_rni_admin_status_updated", plan.get("key"), plan.toString());
        assertFalse(String.valueOf(plan.get("Extra")).contains("Using filesort"), plan.toString());
        Map<String, Object> allStatuses = jdbc.queryForMap("""
                EXPLAIN SELECT id, event_kind, job_id, recipient_id, generation_no, terminal_revision,
                    source_error_code, status, attempts, max_attempts, redrive_round, next_attempt_at,
                    last_error_code, notification_id, redrive_admin_id, redriven_at, redrive_reason,
                    created_at, updated_at FROM report_notification_intent
                WHERE 1=1 ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?
                """, 20, 0);
        System.out.println("ADMIN_QUEUE_ALL_EXPLAIN " + allStatuses);
    }

    private long insertIntent(long job, String suffix, String status, String due) {
        String eventKey = "query-plan:" + classId + ":" + suffix;
        jdbc.update("""
                INSERT INTO report_notification_intent(event_key,event_kind,job_id,class_id,student_id,recipient_id,
                    generation_no,terminal_revision,status,next_attempt_at,dispatch_owner,dispatch_lease_until)
                VALUES (?,'REPORT_READY',?,?,?,1,1,1,?,?,?,?)
                """, eventKey, job, classId, classId, status, "PENDING".equals(status) ? due : null,
                "DISPATCHING".equals(status) ? "synthetic-owner" : null,
                "DISPATCHING".equals(status) ? due : null);
        return jdbc.queryForObject("SELECT id FROM report_notification_intent WHERE event_key=?", Long.class, eventKey);
    }

    @Test void actualMappedQueriesUsePlannedIndexes() throws Exception {
        insert("PENDING", "2020-01-01 00:00:00", null, null);
        long job = insert("GENERATED", "2020-01-01 00:00:00", null, null);
        addPlanFixtures(job);
        LocalDateTime cursor = LocalDateTime.of(1000,1,1,0,0);
        assertPlan("selectExpiredDueCandidates", "ix_report_job_expired_lease", cursor, cursor, 0L, 100);
        assertPlan("selectPendingDueCandidates", "ix_report_job_due", cursor, cursor, 0L, 100);
        assertPlan("selectExpiredTerminalForUpdate", "ix_report_job_retention", 12, 12, 500);
        assertSqlPlan(discoverySql("PENDING_DISCOVERY_SQL"), "ix_rni_pending_due", 100);
        assertSqlPlan(discoverySql("EXPIRED_DISCOVERY_SQL"), "ix_rni_expired_lease", 100);
    }

    private void addPlanFixtures(long job) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            String status = i % 10 == 0 ? "PENDING" : i % 10 == 1 ? "DISPATCHING" : "FAILED";
            String time = i % 100 < 2 ? "2020-01-01 00:00:00" : "2999-01-01 00:00:00";
            rows.add(new Object[]{"query-plan:" + classId + ":plan:" + i, job, classId, classId, status,
                    "PENDING".equals(status) ? time : null,
                    "DISPATCHING".equals(status) ? "synthetic-owner" : null,
                    "DISPATCHING".equals(status) ? time : null});
        }
        jdbc.batchUpdate("""
                INSERT INTO report_notification_intent(event_key,event_kind,job_id,class_id,student_id,recipient_id,
                    generation_no,terminal_revision,status,next_attempt_at,dispatch_owner,dispatch_lease_until)
                VALUES (?,'REPORT_READY',?,?,?,1,1,1,?,?,?,?)
                """, rows);
        jdbc.execute("ANALYZE TABLE report_notification_intent");
    }

    private void assertPlan(String method, String expected, Object... args) {
        String id = ReportJobMapper.class.getName() + "." + method;
        String sql = sessions.getConfiguration().getMappedStatement(id).getBoundSql(Map.of(
                "months",12,"limit",500,"afterTime",LocalDateTime.of(1000,1,1,0,0),"afterId",0L)).getSql();
        assertSqlPlan(sql, expected, args);
    }

    private String discoverySql(String fieldName) throws Exception {
        Field field = ReportNotificationDispatchRepository.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private void assertSqlPlan(String sql, String expected, Object... args) {
        Map<String,Object> plan = jdbc.queryForMap("EXPLAIN " + sql, args);
        System.out.println("REPORT_QUERY_EXPLAIN " + expected + " " + plan);
        assertEquals(expected, plan.get("key"), plan.toString());
        assertNotEquals("ALL", plan.get("type"), plan.toString());
        assertNotEquals("index", plan.get("type"), "full index scan: " + plan);
    }
}
