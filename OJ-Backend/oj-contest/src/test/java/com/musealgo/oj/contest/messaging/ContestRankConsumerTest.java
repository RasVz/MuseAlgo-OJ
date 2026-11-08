package com.musealgo.oj.contest.messaging;

import org.junit.jupiter.api.Test;
import com.musealgo.oj.api.submission.ContestSubmission;
import com.musealgo.oj.api.submission.ContestSubmissionQuery;
import com.musealgo.oj.api.submission.SubmissionJudgedEvent;
import com.musealgo.oj.contest.sse.ContestRankSsePublisher;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContestRankConsumerTest {

    @Test
    void vpSubmissionNeverUpdatesOfficialRank() throws Exception {
        ContestSubmissionQuery query = mock(ContestSubmissionQuery.class);
        var rankSsePublisher = mock(ContestRankSsePublisher.class);
        when(query.findById(5L)).thenReturn(Optional.of(new ContestSubmission(
                5L, 1L, 9L, 10L, 100L, (byte) 1, "cpp", LocalDateTime.now(), 100)));
        ContestRankConsumer consumer = new ContestRankConsumer(query, rankSsePublisher);
        String payload = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(new SubmissionJudgedEvent(5L, 8L, LocalDateTime.now().toString()));

        consumer.onSubmissionJudged(payload);

        verify(rankSsePublisher, never()).officialAndGlobalChanged(1L);
    }

    @Test
    void judgedOfficialSubmissionInvalidatesBothAuthoritativeBoards() throws Exception {
        ContestSubmissionQuery query = mock(ContestSubmissionQuery.class);
        ContestRankSsePublisher rankSsePublisher = mock(ContestRankSsePublisher.class);
        when(query.findById(5L)).thenReturn(Optional.of(new ContestSubmission(
                5L, 1L, null, 10L, 100L, (byte) 1, "cpp", LocalDateTime.now(), 100)));
        ContestRankConsumer consumer = new ContestRankConsumer(query, rankSsePublisher);
        String payload = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(new SubmissionJudgedEvent(5L, 8L, LocalDateTime.now().toString()));

        consumer.onSubmissionJudged(payload);

        verify(rankSsePublisher).officialAndGlobalChanged(1L);
    }
}
