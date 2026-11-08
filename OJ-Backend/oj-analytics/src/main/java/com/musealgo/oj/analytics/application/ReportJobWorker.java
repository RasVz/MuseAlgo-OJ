package com.musealgo.oj.analytics.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.musealgo.oj.analytics.application.ReportApplicationService.PreparedReport;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixed-slot, database-backed worker. Render work runs after the claim transaction commits. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportJobWorker {

    /**
     * Distinct non-zero exit code for the unhealthy self-termination path, so an operator can tell a
     * deliberate recovery exit apart from an OOM kill or a crash inside the renderer.
     */
    static final int UNHEALTHY_EXIT_CODE = 70;

    private final ReportJobLifecycleService lifecycleService;
    private final ReportApplicationService reportApplicationService;
    private final ApplicationEventPublisher eventPublisher;
    private final ConfigurableApplicationContext applicationContext;

    @Value("${report.worker.enabled:true}")
    private boolean enabled;

    @Value("${report.worker.threads:2}")
    private int configuredThreads;

    @Value("${report.worker.max-runtime-seconds:90}")
    private int maxRuntimeSeconds;

    @Value("${report.worker.heartbeat-interval-ms:10000}")
    private int heartbeatIntervalMillis;

    @Value("${report.worker.cancel-grace-seconds:5}")
    private int cancelGraceSeconds;

    @Value("${report.worker.recover-legacy-generating:false}")
    private boolean recoverLegacyGenerating;

    @Value("${report.worker.exit-on-unhealthy:false}")
    private boolean exitOnUnhealthy;

    @Value("${report.worker.exit-grace-seconds:5}")
    private int exitGraceSeconds;

    @Value("${report.worker.exit-hard-timeout-seconds:20}")
    private int exitHardTimeoutSeconds;

    private final AtomicBoolean unhealthy = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger threadSequence = new AtomicInteger();
    private final Map<ExecutionKey, ActiveExecution> activeExecutions = new ConcurrentHashMap<>();

    private Semaphore slots;
    private ThreadPoolExecutor executor;
    private int slotCount;

    @PostConstruct
    void initialize() {
        if (!enabled) return;
        slotCount = Math.max(1, configuredThreads);
        slots = new Semaphore(slotCount);
        ThreadFactory threads = task -> {
            Thread worker = new Thread(task, "report-worker-" + threadSequence.incrementAndGet());
            worker.setDaemon(true);
            return worker;
        };
        executor = new ThreadPoolExecutor(slotCount, slotCount, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), threads, new ThreadPoolExecutor.AbortPolicy());
        executor.prestartAllCoreThreads();

        reportReleaseGate();
        if (recoverLegacyGenerating) {
            try {
                int recovered = 0;
                int batch;
                do {
                    batch = lifecycleService.recoverLegacyGenerating();
                    recovered += batch;
                } while (batch == 500 && enabled && !closed.get());
                log.info("Report worker safely recovered {} pre-lease legacy jobs", recovered);
            } catch (RuntimeException error) {
                markUnhealthy("legacy-recovery-startup-failed");
                log.error("Report worker startup recovery failed; claims remain disabled ({})",
                        error.getClass().getSimpleName());
            }
        }
    }

    /**
     * Release gate for the V64 upgrade. Rows left {@code GENERATING} with no lease belonged to the
     * pre-lease synchronous worker; they are only recovered when the operator opts in, because the old
     * instance has to be stopped first (see docs/ARCHITECTURE.md#十报告任务生命周期). The count is logged either way so
     * the deployment checklist has a machine-checkable signal instead of relying on memory.
     */
    private void reportReleaseGate() {
        int legacy;
        try {
            legacy = lifecycleService.countLegacyGenerating();
        } catch (RuntimeException error) {
            log.warn("OJ_RELEASE_GATE report_worker legacy_generating=unknown recover_legacy={} ({})",
                    recoverLegacyGenerating, error.getClass().getSimpleName());
            return;
        }
        log.info("OJ_RELEASE_GATE report_worker legacy_generating={} recover_legacy={}",
                legacy, recoverLegacyGenerating);
        if (legacy > 0 && !recoverLegacyGenerating) {
            log.error("OJ_ALERT report_worker_legacy_rows_present count={} — pre-lease rows are not "
                            + "recovered while report.worker.recover-legacy-generating=false; confirm the old "
                            + "worker is stopped, then drain them or opt in and restart",
                    legacy);
        }
    }

    /** Claim only after reserving a real execution slot; no JVM queue can accumulate jobs. */
    @Scheduled(fixedDelayString = "${report.worker.poll-interval-ms:2000}",
            initialDelayString = "${report.worker.poll-interval-ms:2000}")
    public void claimAndDispatch() {
        if (!canClaim() || !slots.tryAcquire()) return;
        ReportJobExecution execution;
        try {
            execution = lifecycleService.claimNext();
        } catch (RuntimeException error) {
            slots.release();
            log.warn("Report worker claim unavailable ({})", error.getClass().getSimpleName());
            return;
        }
        if (execution == null) {
            slots.release();
            return;
        }

        ActiveExecution active = new ActiveExecution(execution, System.nanoTime(), heartbeatIntervalMillis);
        ExecutionKey key = new ExecutionKey(execution.jobId(), execution.generationNo());
        if (activeExecutions.putIfAbsent(key, active) != null) {
            slots.release();
            recordUnscheduledFailure(execution);
            return;
        }
        try {
            executor.execute(() -> run(active));
        } catch (RejectedExecutionException error) {
            activeExecutions.remove(key, active);
            slots.release();
            recordUnscheduledFailure(execution);
        }
    }

    /** One-second watchdog checks runtime/grace while DB lease renewals stay at the configured interval. */
    @Scheduled(fixedDelayString = "${report.worker.watchdog-interval-ms:1000}",
            initialDelayString = "${report.worker.watchdog-interval-ms:1000}")
    public void heartbeatAndWatchdog() {
        if (!enabled || closed.get()) return;
        long now = System.nanoTime();
        long maxRuntimeNanos = TimeUnit.SECONDS.toNanos(Math.max(1, maxRuntimeSeconds));
        long graceNanos = TimeUnit.SECONDS.toNanos(Math.max(1, cancelGraceSeconds));
        long heartbeatNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1000, heartbeatIntervalMillis));

        for (ActiveExecution active : activeExecutions.values()) {
            long elapsed = now - active.startedNanos;
            if (elapsed >= maxRuntimeNanos) {
                requestCancellation(active, now);
                if (now - active.deadlineObservedNanos >= graceNanos
                        && active.runner != null && active.runner.isAlive()) {
                    markUnhealthy("worker-runtime-deadline-exceeded");
                }
                continue;
            }
            if (now < active.nextHeartbeatNanos) continue;
            active.nextHeartbeatNanos = now + heartbeatNanos;
            try {
                if (!lifecycleService.heartbeat(active.execution)) {
                    requestCancellation(active, now);
                }
            } catch (RuntimeException error) {
                // Do not claim or renew through an unavailable database; the DB lease will expire.
                log.warn("Report worker heartbeat unavailable jobId={} ({})",
                        active.execution.jobId(), error.getClass().getSimpleName());
            }
        }
    }

    public boolean isHealthy() {
        return !unhealthy.get();
    }

    public int activeCount() {
        return activeExecutions.size();
    }

    private boolean canClaim() {
        return enabled && !closed.get() && !unhealthy.get() && executor != null && !executor.isShutdown();
    }

    private void run(ActiveExecution active) {
        active.runner = Thread.currentThread();
        ReportJobExecution execution = active.execution;
        try {
            PreparedReport prepared;
            try {
                prepared = reportApplicationService.prepareWeeklyReportInternal(execution.studentId(),
                        execution.classId(), execution.periodStart(), execution.periodEnd());
            } catch (RuntimeException error) {
                recordRendererFailure(execution, ReportFailurePolicy.classifyRendererFailure(error), error);
                return;
            }
            if (prepared == null || prepared.profile() == null
                    || prepared.html() == null || prepared.html().isBlank()) {
                recordRendererFailure(execution, "EMPTY_RENDER", null);
                return;
            }

            if (!markFinalizingUntilDeadline(active)) return;
            while (withinRuntime(active) && !Thread.currentThread().isInterrupted()) {
                try {
                    if (lifecycleService.finalizeGenerated(execution, prepared.profile(), prepared.html())) return;
                    return; // CAS rejection means the generation no longer owns the job.
                } catch (RuntimeException error) {
                    if (!withinRuntime(active) || Thread.currentThread().isInterrupted()) return;
                    log.warn("Report finalizer will retry under its current lease jobId={} ({})",
                            execution.jobId(), error.getClass().getSimpleName());
                    if (!pauseBeforeRetry()) return;
                }
            }
        } finally {
            active.runner = null;
            activeExecutions.remove(new ExecutionKey(execution.jobId(), execution.generationNo()), active);
            slots.release();
        }
    }

    private boolean markFinalizingUntilDeadline(ActiveExecution active) {
        while (withinRuntime(active) && !Thread.currentThread().isInterrupted()) {
            try {
                return lifecycleService.markFinalizing(active.execution);
            } catch (RuntimeException error) {
                if (!withinRuntime(active) || Thread.currentThread().isInterrupted()) return false;
                log.warn("Report finalizing marker will retry under its current lease jobId={} ({})",
                        active.execution.jobId(), error.getClass().getSimpleName());
                if (!pauseBeforeRetry()) return false;
            }
        }
        return false;
    }

    private void recordRendererFailure(ReportJobExecution execution, String code, RuntimeException cause) {
        if (cause != null) {
            log.warn("Report renderer failed jobId={} code={} type={}", execution.jobId(), code,
                    cause.getClass().getSimpleName());
        }
        try {
            lifecycleService.recordGenerationFailure(execution, code);
        } catch (RuntimeException error) {
            // A failed state/notice transaction leaves GENERATING fenced for lease recovery.
            log.error("Report failure could not be committed jobId={} ({})", execution.jobId(),
                    error.getClass().getSimpleName());
        }
    }

    private void recordUnscheduledFailure(ReportJobExecution execution) {
        try {
            lifecycleService.recordGenerationFailure(execution, "GENERATOR_UNAVAILABLE");
        } catch (RuntimeException error) {
            log.error("Claimed report could not be dispatched or safely requeued jobId={} ({})",
                    execution.jobId(), error.getClass().getSimpleName());
        }
    }

    private boolean withinRuntime(ActiveExecution active) {
        return System.nanoTime() - active.startedNanos
                < TimeUnit.SECONDS.toNanos(Math.max(1, maxRuntimeSeconds));
    }

    private boolean pauseBeforeRetry() {
        try {
            Thread.sleep(200L);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void requestCancellation(ActiveExecution active, long now) {
        if (active.deadlineObservedNanos == 0) {
            active.deadlineObservedNanos = now;
            Thread runner = active.runner;
            if (runner != null) runner.interrupt();
        }
    }

    private void markUnhealthy(String reason) {
        if (!unhealthy.compareAndSet(false, true)) return;
        AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.REFUSING_TRAFFIC);
        // Keeps the "readiness is DOWN" phrasing the batch-3 watchdog drill greps for, so that
        // harness stays replayable, while OJ_ALERT gives deployment-side alerting a stable marker.
        log.error("OJ_ALERT report_worker_unhealthy reason={} activeJobs={} exitOnUnhealthy={} — "
                        + "Report worker readiness is DOWN; it will not reclaim slots until process restart",
                reason, activeCount(), exitOnUnhealthy);
        if (exitOnUnhealthy) selfTerminate(reason);
    }

    /**
     * Hands the recovery decision to the orchestrator by making the process disappear, which is the
     * only unambiguous signal a Compose {@code restart} policy can act on. Enable this exclusively on a
     * worker-only instance: a process that also serves HTTP would take user traffic down with it.
     * <p>
     * The stuck renderer thread is by definition uninterruptible, so graceful shutdown may never return.
     * A daemon deadline thread therefore guarantees the exit still happens via {@link Runtime#halt}.
     * Unfinished GENERATING/FINALIZING rows are not lost: their DB lease expires and the restarted
     * worker reclaims them, which costs one attempt.
     */
    private void selfTerminate(String reason) {
        Thread terminator = new Thread(() -> {
            sleepBeforeExit(exitGraceSeconds);
            log.error("OJ_ALERT report_worker_self_terminate reason={} exitCode={}", reason, UNHEALTHY_EXIT_CODE);
            try {
                applicationContext.close();
                System.exit(UNHEALTHY_EXIT_CODE);
            } catch (RuntimeException | Error ignored) {
                Runtime.getRuntime().halt(UNHEALTHY_EXIT_CODE);
            }
        }, "report-worker-self-terminate");
        terminator.setDaemon(false);
        terminator.start();

        Thread deadline = new Thread(() -> {
            sleepBeforeExit(exitGraceSeconds + exitHardTimeoutSeconds);
            log.error("OJ_ALERT report_worker_self_terminate_timeout reason={} exitCode={}",
                    reason, UNHEALTHY_EXIT_CODE);
            Runtime.getRuntime().halt(UNHEALTHY_EXIT_CODE);
        }, "report-worker-exit-deadline");
        deadline.setDaemon(true);
        deadline.start();
    }

    private static void sleepBeforeExit(int seconds) {
        try {
            TimeUnit.SECONDS.sleep(Math.max(0, seconds));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void shutdown() {
        closed.set(true);
        if (executor != null) executor.shutdownNow();
    }

    /** 同一任务的旧代次可能仍在运行，必须分别跟踪其取消与槽位。 */
    private record ExecutionKey(Long jobId, long generationNo) { }

    private static final class ActiveExecution {
        private final ReportJobExecution execution;
        private final long startedNanos;
        private volatile long deadlineObservedNanos;
        private volatile long nextHeartbeatNanos;
        private volatile Thread runner;

        private ActiveExecution(ReportJobExecution execution, long startedNanos, int heartbeatIntervalMillis) {
            this.execution = execution;
            this.startedNanos = startedNanos;
            this.nextHeartbeatNanos = startedNanos
                    + TimeUnit.MILLISECONDS.toNanos(Math.max(1000, heartbeatIntervalMillis));
        }
    }
}
