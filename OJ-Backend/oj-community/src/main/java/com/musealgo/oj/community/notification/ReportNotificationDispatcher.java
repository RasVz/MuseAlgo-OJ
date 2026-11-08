package com.musealgo.oj.community.notification;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import com.musealgo.oj.community.service.NotificationService;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Single-thread, database-backed dispatcher for report notification intents.
 * <p>
 * There is no message-broker dependency: disabled polling simply leaves committed intents in
 * {@code PENDING}, and re-enabling resumes from the database. Delivery is intentionally IN_APP
 * only; no external provider is invoked.
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportNotificationDispatcher {

    private final ReportNotificationDispatchRepository repository;
    private final NotificationService notificationService;

    @Value("${report.notification.worker-enabled:true}")
    private boolean workerEnabled;

    @Value("${report.notification.poll-interval-ms:2000}")
    private long pollIntervalMillis;

    @Value("${report.notification.claim-batch-size:10}")
    private int configuredBatchSize;

    private ScheduledExecutorService scheduler;

    @PostConstruct
    void startScheduler() {
        if (!workerEnabled) return;
        long interval = Math.max(500L, pollIntervalMillis);
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "report-notification-dispatcher");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::scheduledPoll, interval, interval, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stopScheduler() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** Scheduler entry point. */
    public void scheduledPoll() {
        if (!workerEnabled) return;
        try {
            dispatchDue(configuredBatchSize);
        } catch (RuntimeException error) {
            log.warn("Report notification poll unavailable ({})", error.getClass().getSimpleName());
        }
    }

    /** Claims and dispatches due intents sequentially; exposed for controlled operations/tests. */
    public int dispatchDue(int limit) {
        List<Long> due = repository.findDueIntentIds(limit);
        int delivered = 0;
        for (Long intentId : due) {
            if (dispatchIntent(intentId)) delivered++;
        }
        return delivered;
    }

    /**
     * Runs one claim, then delivery outside the claim transaction. The return value means a new
     * in-app notification was committed; skips, fenced losses, and retryable failures return false.
     */
    public boolean dispatchIntent(Long intentId) {
        if (intentId == null) return false;
        ReportNotificationClaim claim;
        try {
            claim = repository.claimIntent(intentId);
        } catch (RuntimeException error) {
            log.warn("Report notification claim unavailable ({})", error.getClass().getSimpleName());
            return false;
        }
        if (claim == null) return false;

        NotificationService.NotificationPrefs preferences;
        try {
            preferences = notificationService.loadReportPreferencesStrict(claim.recipientId());
        } catch (RuntimeException error) {
            recordFailureSafely(claim, "PREFERENCE_READ_FAILED");
            return false;
        }
        if (!preferences.reports()) {
            // Preference off is a business skip, not a dispatch/system failure.
            try {
                repository.skipForPreference(claim);
            } catch (RuntimeException error) {
                log.warn("Report notification preference skip unavailable ({})",
                        error.getClass().getSimpleName());
            }
            return false;
        }

        try {
            return repository.deliver(claim)
                    == ReportNotificationDispatchRepository.DeliveryOutcome.DELIVERED;
        } catch (RuntimeException error) {
            recordFailureSafely(claim, failureCode(error));
            return false;
        }
    }

    private void recordFailureSafely(ReportNotificationClaim claim, String code) {
        try {
            repository.recordFailure(claim, code);
        } catch (RuntimeException error) {
            // The committed claim already consumed budget; the lease-expiry scanner will converge.
            log.error("Report notification failure record could not be committed ({})",
                    error.getClass().getSimpleName());
        }
    }

    private static String failureCode(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof DataAccessException || current instanceof SQLException) {
                return "NOTIFICATION_STORE_UNAVAILABLE";
            }
        }
        return "NOTIFICATION_DELIVERY_FAILED";
    }
}
