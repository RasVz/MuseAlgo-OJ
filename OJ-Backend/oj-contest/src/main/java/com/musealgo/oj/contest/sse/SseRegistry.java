package com.musealgo.oj.contest.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Holds rank invalidation streams by contest and board type. SSE frames never
 * carry rank rows: clients re-read the matching authoritative GET endpoint.
 */
@Slf4j
@Component
public class SseRegistry {

    private final Map<ContestRankSubscription, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Supplier<List<SseEmitter>> emitterListFactory;

    public SseRegistry() {
        this(CopyOnWriteArrayList::new);
    }

    SseRegistry(Supplier<List<SseEmitter>> emitterListFactory) {
        this.emitterListFactory = emitterListFactory;
    }

    public Set<ContestRankSubscription> activeSubscriptions() {
        return Set.copyOf(emitters.keySet());
    }

    public void register(Long contestId, ContestRankBoardType boardType, SseEmitter emitter) {
        ContestRankSubscription subscription = new ContestRankSubscription(contestId, boardType);
        emitters.compute(subscription, (key, current) -> {
            List<SseEmitter> list = current == null ? emitterListFactory.get() : current;
            list.add(emitter);
            return list;
        });
        emitter.onCompletion(() -> remove(subscription, emitter));
        emitter.onTimeout(() -> remove(subscription, emitter));
        emitter.onError(error -> remove(subscription, emitter));
        log.debug("SSE 连接注册 contestId={}, boardType={}, total={}", contestId, boardType,
                emitters.getOrDefault(subscription, List.of()).size());
    }

    /**
     * Ask listeners of one board to re-read its authoritative query. The only
     * payload is the board name, so a stream opened before an OI contest starts
     * cannot turn into a ranking-data channel during the contest.
     */
    public void broadcastInvalidation(Long contestId, ContestRankBoardType boardType) {
        ContestRankSubscription subscription = new ContestRankSubscription(contestId, boardType);
        List<SseEmitter> list = emitters.get(subscription);
        if (list == null || list.isEmpty()) return;

        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name("ranking-invalidated")
                        .data(boardType.wireValue()));
            } catch (IOException | IllegalStateException exception) {
                remove(subscription, emitter);
            }
        }
    }

    private void remove(ContestRankSubscription subscription, SseEmitter emitter) {
        emitters.computeIfPresent(subscription, (key, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }
}
