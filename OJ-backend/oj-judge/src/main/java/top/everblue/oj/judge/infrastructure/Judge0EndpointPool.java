package top.everblue.oj.judge.infrastructure;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Judge0 多端点池：轮询分发 + 失败熔断（去单点）+ 在途请求配额（防超时雪崩）。
 *
 * <h3>配置</h3>
 * <ul>
 *   <li>{@code judge0.urls}：逗号分隔的多端点列表，优先级高于旧的 {@code judge0.url}；
 *       未配置时回退到单端点（行为与改造前完全一致）。</li>
 *   <li>{@code judge0.breaker.failure-threshold}：连续失败次数达到阈值后熔断，默认 3。</li>
 *   <li>{@code judge0.breaker.cooldown-ms}：熔断冷却时长，冷却期满后自动半开试探，默认 60000ms。</li>
 *   <li>{@code judge0.max-inflight}：全局在途请求上限（信号量，风险 1 预案）。
 *       必须 ≤ Judge0 可用 worker 槽位数（worker 数 × 每 worker 并发），
 *       否则 wait=true 的用例请求会在 Judge0 队列中等待超过应用读超时，
 *       造成超时 → 重试 → 更堵的超时雪崩。默认 4（对齐 2~3 个 worker 的容量）。</li>
 * </ul>
 *
 * <h3>语义</h3>
 * <ul>
 *   <li>轮询只经过"健康 + 冷却期满"的端点；全部被熔断时退化为全员可用（宁可尝试不可用的，也不直接失败）。</li>
 *   <li>失败计数按端点隔离，成功一次即清零（半开试探成功自动恢复）。</li>
 *   <li>{@link #tryAcquire} / {@link #release} 为用例请求提供信号量配额：
 *       在途请求数封顶后，新增请求阻塞等待（与读超时同量级），把背压挡在应用侧而不是 Judge0 队列。</li>
 * </ul>
 */
@Slf4j
@Component
public class Judge0EndpointPool {

    /** 多端点列表，如 http://judge0-1:2358,http://judge0-2:2358 */
    @Value("${judge0.urls:}")
    private String urlsConfig;

    /** 旧版单端点配置，作为兼容回退 */
    @Value("${judge0.url:http://localhost:2358}")
    private String legacyUrl;

    @Value("${judge0.breaker.failure-threshold:3}")
    private int failureThreshold;

    @Value("${judge0.breaker.cooldown-ms:60000}")
    private long cooldownMs;

    /** 全局在途请求上限（对齐 Judge0 worker 槽位，防超时雪崩） */
    @Value("${judge0.max-inflight:4}")
    private int maxInflight;

    private final List<String> endpoints = new ArrayList<>();
    private final Map<String, EndpointState> states = new ConcurrentHashMap<>();
    private final AtomicInteger roundRobin = new AtomicInteger(0);
    private Semaphore inflightSemaphore;

    @PostConstruct
    void init() {
        if (urlsConfig != null && !urlsConfig.isBlank()) {
            for (String part : urlsConfig.split(",")) {
                String url = part.trim();
                if (!url.isEmpty()) {
                    endpoints.add(stripTrailingSlash(url));
                }
            }
        }
        if (endpoints.isEmpty()) {
            endpoints.add(stripTrailingSlash(legacyUrl));
        }
        endpoints.forEach(url -> states.put(url, new EndpointState()));
        this.inflightSemaphore = new Semaphore(Math.max(1, maxInflight));
        log.info("【Judge0】端点池初始化完成 endpoints={}, failureThreshold={}, cooldownMs={}, maxInflight={}",
                endpoints, failureThreshold, cooldownMs, maxInflight);
    }

    /**
     * 申请一个在途请求额度；申请不到则阻塞等待，超时返回 false。
     *
     * @param timeoutMs 等待上限（与读超时同量级）
     */
    public boolean tryAcquire(long timeoutMs) {
        try {
            return inflightSemaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 释放一个在途请求额度（与 tryAcquire 成对调用，finally 中释放） */
    public void release() {
        inflightSemaphore.release();
    }

    /**
     * 返回本次请求可尝试的端点顺序（从轮询游标开始，优先健康节点）。
     * 全部熔断中时也返回全量列表，保证请求有机会打到"冷却刚结束"的节点。
     */
    public List<String> candidates() {
        List<String> available = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String url : endpoints) {
            EndpointState state = states.get(url);
            if (state == null || !state.isCircuitOpen(now, cooldownMs)) {
                available.add(url);
            }
        }
        List<String> pool = available.isEmpty() ? endpoints : available;
        // 以轮询游标为起点旋转列表，均衡负载
        int size = pool.size();
        int start = Math.floorMod(roundRobin.getAndIncrement(), size);
        List<String> ordered = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ordered.add(pool.get((start + i) % size));
        }
        return ordered;
    }

    /** 调用成功：清零失败计数并解除熔断（半开节点恢复为健康） */
    public void onSuccess(String endpoint) {
        EndpointState state = states.get(endpoint);
        if (state == null) {
            return;
        }
        if (state.consecutiveFailures.get() > 0) {
            log.info("【Judge0】端点恢复健康 endpoint={}", endpoint);
        }
        state.consecutiveFailures.set(0);
        state.openedAt = 0;
    }

    /** 调用失败：累计失败，达到阈值后熔断 cooldownMs */
    public void onFailure(String endpoint) {
        EndpointState state = states.get(endpoint);
        if (state == null) {
            return;
        }
        int failures = state.consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold && state.openedAt == 0) {
            state.openedAt = System.currentTimeMillis();
            log.warn("【Judge0】端点熔断 endpoint={}, consecutiveFailures={}, cooldown={}ms",
                    endpoint, failures, cooldownMs);
        } else if (failures >= failureThreshold) {
            // 半开试探再次失败：重新计时冷却
            state.openedAt = System.currentTimeMillis();
            log.warn("【Judge0】端点半开试探失败，继续熔断 endpoint={}", endpoint);
        }
    }

    /** 当前端点总数（监控/日志用） */
    public int size() {
        return endpoints.size();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 单个端点的熔断状态（线程安全） */
    private static final class EndpointState {
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        /** 熔断开始时间；0 = 未熔断。读取允许竞态（最坏多放行一次半开试探，可接受） */
        private volatile long openedAt = 0;

        private boolean isCircuitOpen(long now, long cooldownMs) {
            // 冷却期满 → 视为半开，candidates() 会放行一次试探
            return openedAt != 0 && (now - openedAt) < cooldownMs;
        }
    }
}
