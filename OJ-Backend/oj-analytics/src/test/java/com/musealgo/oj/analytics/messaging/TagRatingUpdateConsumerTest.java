package com.musealgo.oj.analytics.messaging;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musealgo.oj.analytics.application.TagRatingService;
import com.musealgo.oj.api.submission.SubmissionQuery;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class TagRatingUpdateConsumerTest {
 @Test void dependencyFailureMustNotBeAcknowledgedAsSuccessfulProcessing() {
  var query = mock(SubmissionQuery.class); var rating = mock(TagRatingService.class);
  when(query.findById(1L)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic"));
  var consumer = new TagRatingUpdateConsumer(query, rating, new ObjectMapper());
  assertThrows(RuntimeException.class, () -> consumer.handle("{\"submissionId\":1,\"taskId\":1,\"judgedAt\":\"synthetic\"}"));
 }
}
