package top.everblue.oj.contest.messaging;

import org.junit.jupiter.api.Test;
import top.everblue.oj.api.submission.ContestSubmission;
import top.everblue.oj.api.submission.ContestSubmissionQuery;
import top.everblue.oj.api.submission.SubmissionJudgedEvent;
import top.everblue.oj.contest.mapper.ContestRankMapper;
import top.everblue.oj.contest.service.RankRedisService;
import top.everblue.oj.contest.sse.SseRegistry;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContestRankConsumerTest {

    @Test
    void vpSubmissionNeverUpdatesOfficialRank() throws Exception {
        ContestSubmissionQuery query = mock(ContestSubmissionQuery.class);
        var ranks = mock(top.everblue.oj.contest.service.ContestRankService.class);
        when(query.findById(5L)).thenReturn(Optional.of(new ContestSubmission(
                5L, 1L, 9L, 10L, 100L, (byte) 1, "cpp", LocalDateTime.now(), 100)));
        ContestRankConsumer consumer = new ContestRankConsumer(query, ranks, mock(SseRegistry.class));
        String payload = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(new SubmissionJudgedEvent(5L, 8L, LocalDateTime.now().toString()));

        consumer.onSubmissionJudged(payload);

        verify(ranks, never()).getRealtimeTopN(any(), any());
    }
}
