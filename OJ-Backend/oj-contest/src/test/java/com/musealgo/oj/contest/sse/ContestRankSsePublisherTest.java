package com.musealgo.oj.contest.sse;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ContestRankSsePublisherTest {

    @Test
    void officialChangesPublishOnlyTypedOfficialAndGlobalInvalidations() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        SseRegistry registry = mock(SseRegistry.class);
        ContestRankSsePublisher publisher = new ContestRankSsePublisher(eventPublisher, registry);

        publisher.officialAndGlobalChanged(7L);

        verify(eventPublisher).publishEvent(new ContestRankInvalidationEvent(7L, ContestRankBoardType.OFFICIAL));
        verify(eventPublisher).publishEvent(new ContestRankInvalidationEvent(7L, ContestRankBoardType.GLOBAL));
        verifyNoInteractions(registry);
    }

    @Test
    void vpChangesPublishOnlyGlobalInvalidation() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        SseRegistry registry = mock(SseRegistry.class);
        ContestRankSsePublisher publisher = new ContestRankSsePublisher(eventPublisher, registry);

        publisher.globalChanged(8L);

        verify(eventPublisher).publishEvent(new ContestRankInvalidationEvent(8L, ContestRankBoardType.GLOBAL));
        verify(eventPublisher, never()).publishEvent(new ContestRankInvalidationEvent(8L, ContestRankBoardType.OFFICIAL));
    }

    @Test
    void resendUsesActiveBoardSubscriptionsWithoutConsultingMessaging() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        SseRegistry registry = mock(SseRegistry.class);
        when(registry.activeSubscriptions()).thenReturn(Set.of(
                new ContestRankSubscription(9L, ContestRankBoardType.OFFICIAL),
                new ContestRankSubscription(9L, ContestRankBoardType.GLOBAL)));
        ContestRankSsePublisher publisher = new ContestRankSsePublisher(eventPublisher, registry);

        publisher.resendForActiveSubscriptions();

        verify(registry).broadcastInvalidation(9L, ContestRankBoardType.OFFICIAL);
        verify(registry).broadcastInvalidation(9L, ContestRankBoardType.GLOBAL);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void committedEventIsRoutedToItsBoardOnly() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        SseRegistry registry = mock(SseRegistry.class);
        ContestRankSsePublisher publisher = new ContestRankSsePublisher(eventPublisher, registry);

        publisher.afterRankChangeCommitted(new ContestRankInvalidationEvent(10L, ContestRankBoardType.GLOBAL));

        verify(registry).broadcastInvalidation(10L, ContestRankBoardType.GLOBAL);
    }
}
