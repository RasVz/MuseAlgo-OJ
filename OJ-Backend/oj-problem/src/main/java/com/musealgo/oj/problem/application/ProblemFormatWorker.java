package com.musealgo.oj.problem.application;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.musealgo.oj.problem.entity.ProblemFormatJob;
import com.musealgo.oj.problem.entity.ProblemFormatJobItem;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 批量题面整理的执行器。
 * <p>
 * 固定并发（默认 2）而不是尽最大努力并行：每次整理都是一次付费模型调用，
 * 突发 30 题同时打满上游既没有意义，也容易触发供应商限流。
 * </p>
 * <p>
 * 可靠性靠数据库状态机而不是内存队列：领取用 {@code UPDATE ... WHERE status='PENDING'} 原子完成，
 * 进程重启后由启动恢复把遗留 RUNNING 任务标成失败，PENDING 任务由定时器重新投递。
 * </p>
 */
@Component
public class ProblemFormatWorker {

    private static final Logger log = LoggerFactory.getLogger(ProblemFormatWorker.class);

    private final ProblemFormatJobService jobService;
    private final ThreadPoolExecutor executor;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicInteger threadIndex = new AtomicInteger();

    @Value("${problem.format.worker.recover-stale-minutes:10}")
    private int recoverStaleMinutes;

    @Value("${problem.format.worker.enabled:true}")
    private boolean enabled;

    public ProblemFormatWorker(ProblemFormatJobService jobService,
                               @Value("${problem.format.worker.threads:2}") int threads) {
        this.jobService = jobService;
        int poolSize = Math.max(1, Math.min(threads, 4));
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "problem-format-" + threadIndex.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = new ThreadPoolExecutor(poolSize, poolSize, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    /** 任务受理后立即尝试投递；投递失败还有定时器兜底，不影响已落库的任务。 */
    @EventListener
    public void onJobCreated(ProblemFormatJobService.ProblemFormatJobCreatedEvent event) {
        if (!enabled) {
            return;
        }
        submit(event.jobId());
    }

    public void submit(Long jobId) {
        if (jobId == null || !inFlight.add(jobId)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    run(jobId);
                } finally {
                    inFlight.remove(jobId);
                }
            });
        } catch (RejectedExecutionException rejected) {
            inFlight.remove(jobId);
            log.warn("题面整理任务排队已满，等待定时器重新投递 jobId={}", jobId);
        }
    }

    void run(Long jobId) {
        if (!jobService.claim(jobId)) {
            // 已被其他执行者领取，或已取消/结束
            return;
        }
        try {
            ProblemFormatJob job = jobService.findJob(jobId);
            if (job == null) {
                return;
            }
            List<ProblemFormatJobItem> items = jobService.pendingItems(jobId);
            for (ProblemFormatJobItem item : items) {
                if (jobService.isCancelRequested(jobId)) {
                    jobService.markRemainingSkipped(jobId);
                    break;
                }
                jobService.processItem(job, item);
            }
            jobService.finish(jobId);
        } catch (RuntimeException e) {
            log.error("题面整理任务执行异常 jobId={}", jobId, e);
            jobService.fail(jobId, "任务执行异常，请重试失败项");
        }
    }

    /** 定时兜底：事件丢失、队列拒绝、进程重启后遗留的 PENDING 任务都会被重新领取。 */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void requeuePending() {
        if (!enabled) {
            return;
        }
        for (Long jobId : jobService.claimableJobIds()) {
            submit(jobId);
        }
    }

    /** 进程重启后没有执行者的 RUNNING 任务只能失败：让教师看到明确原因并单点重试。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOrphanedJobs() {
        if (!enabled) {
            return;
        }
        try {
            int recovered = jobService.failOrphaned(LocalDateTime.now().minusMinutes(Math.max(1, recoverStaleMinutes)),
                    "任务执行中断（服务重启），请重试失败项");
            if (recovered > 0) {
                log.warn("恢复中断的题面整理任务 {} 个", recovered);
            }
        } catch (RuntimeException e) {
            log.warn("恢复中断任务失败：{}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** 供测试观察：当前正在执行的任务数。 */
    int inFlightCount() {
        return inFlight.size();
    }
}
