package com.musealgo.oj.ai.draft;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.musealgo.oj.ai.entity.ProblemDraft;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 题面生成的执行器。
 * <p>
 * 固定并发（默认 2）：每次生成都是一次付费模型调用，突发并发只会互相排队并触发上游限流。
 * 可靠性靠数据库状态机：领取用 {@code UPDATE ... WHERE status='PENDING'} 原子完成，
 * 进程重启后遗留的 RUNNING 由启动恢复标记失败，PENDING 由定时器重新投递。
 * </p>
 */
@Component
public class ProblemDraftWorker {

    private static final Logger log = LoggerFactory.getLogger(ProblemDraftWorker.class);

    /** 一次生成的最长等待：超过这个时间仍未结束，视为进程中断而不是模型慢。 */
    private static final int STALE_MINUTES = 20;

    private final ProblemDraftService draftService;
    private final ThreadPoolExecutor executor;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicInteger threadIndex = new AtomicInteger();

    @Value("${ai.draft.worker.enabled:true}")
    private boolean enabled;

    public ProblemDraftWorker(ProblemDraftService draftService,
                              @Value("${ai.draft.worker.threads:2}") int threads) {
        this.draftService = draftService;
        int poolSize = Math.max(1, Math.min(threads, 4));
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "problem-draft-" + threadIndex.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = new ThreadPoolExecutor(poolSize, poolSize, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    @EventListener
    public void onDraftCreated(ProblemDraftService.DraftCreatedEvent event) {
        if (!enabled) {
            return;
        }
        submit(event.draftId());
    }

    public void submit(Long draftId) {
        if (draftId == null || !inFlight.add(draftId)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    run(draftId);
                } finally {
                    inFlight.remove(draftId);
                }
            });
        } catch (RejectedExecutionException rejected) {
            inFlight.remove(draftId);
            log.warn("题面生成排队已满，等待定时器重新投递 draftId={}", draftId);
        }
    }

    void run(Long draftId) {
        if (!draftService.claim(draftId)) {
            // 已被其他执行者领取，或已取消/删除
            return;
        }
        ProblemDraft draft = draftService.find(draftId);
        if (draft == null) {
            return;
        }
        draftService.process(draft);
    }

    /** 定时兜底：事件丢失、队列拒绝、进程重启后遗留的 PENDING 都会被重新领取。 */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void requeuePending() {
        if (!enabled) {
            return;
        }
        for (Long draftId : draftService.claimableDraftIds()) {
            submit(draftId);
        }
    }

    /**
     * 运行期超时回收：只有启动时才扫一次的话，卡住的 RUNNING 会一直占着「生成中」直到下次重启。
     * 每 10 分钟扫一次，把超过 20 分钟没有更新的草稿判为中断，教师能看到明确原因并重试。
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    public void recoverStaleDrafts() {
        if (!enabled) {
            return;
        }
        sweepStale("生成超时（20 分钟未完成），可重试");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOrphanedDrafts() {
        if (!enabled) {
            return;
        }
        sweepStale("生成被中断（服务重启），可重试");
    }

    private void sweepStale(String message) {
        try {
            int recovered = draftService.failOrphaned(LocalDateTime.now().minusMinutes(STALE_MINUTES), message);
            if (recovered > 0) {
                log.warn("回收未完成的题面生成 {} 个：{}", recovered, message);
            }
        } catch (RuntimeException e) {
            log.warn("回收未完成题面生成失败：{}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** 供测试观察：当前正在执行的草稿数。 */
    int inFlightCount() {
        return inFlight.size();
    }
}
