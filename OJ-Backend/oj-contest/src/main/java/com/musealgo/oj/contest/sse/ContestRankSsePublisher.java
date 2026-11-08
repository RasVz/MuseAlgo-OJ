package com.musealgo.oj.contest.sse;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes committed rank changes and periodically re-sends invalidations to
 * live subscribers. The periodic path is deliberately independent of RabbitMQ
 * so connected clients converge after broker disablement or an outage.
 */
@Component
@RequiredArgsConstructor
public class ContestRankSsePublisher {

    private final ApplicationEventPublisher eventPublisher;
    private final SseRegistry registry;

    public void officialAndGlobalChanged(Long contestId) {
        publish(contestId, ContestRankBoardType.OFFICIAL);
        publish(contestId, ContestRankBoardType.GLOBAL);
    }

    public void globalChanged(Long contestId) {
        publish(contestId, ContestRankBoardType.GLOBAL);
    }

    private void publish(Long contestId, ContestRankBoardType boardType) {
        if (contestId != null) {
            eventPublisher.publishEvent(new ContestRankInvalidationEvent(contestId, boardType));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void afterRankChangeCommitted(ContestRankInvalidationEvent event) {
        if (event != null && event.contestId() != null && event.boardType() != null) {
            registry.broadcastInvalidation(event.contestId(), event.boardType());
        }
    }

    @Scheduled(fixedDelayString = "${oj.contest.rank.sse-resync-interval-ms:15000}",
            initialDelayString = "${oj.contest.rank.sse-resync-initial-delay-ms:15000}")
    public void resendForActiveSubscriptions() {
        for (ContestRankSubscription subscription : registry.activeSubscriptions()) {
            registry.broadcastInvalidation(subscription.contestId(), subscription.boardType());
        }
    }
}
