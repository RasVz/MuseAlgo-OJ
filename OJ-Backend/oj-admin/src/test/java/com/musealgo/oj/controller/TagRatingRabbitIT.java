package com.musealgo.oj.controller;

import com.musealgo.oj.api.submission.SubmissionQuery;
import com.musealgo.oj.analytics.messaging.TagRatingMessagingConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"oj.messaging.rabbitmq-enabled=true", "oj.judge.worker.enabled=false",
 "report.worker.enabled=false", "report.notification.worker-enabled=false", "oj.messaging.outbox.poll-interval-ms=86400000"})
class TagRatingRabbitIT extends SyntheticReviewDatabase {
 @Autowired RabbitTemplate rabbit;
 @MockitoSpyBean SubmissionQuery submissions;
 @Test void dependencyFailureRetriesThreeTimesThenPreservesAnEventWithoutLeakingSql() throws Exception {
  doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic SQL must stay internal"))
      .when(submissions).findById(Long.MAX_VALUE);
  String body="{\"submissionId\":"+Long.MAX_VALUE+",\"taskId\":1,\"judgedAt\":\"synthetic\"}";
  rabbit.convertAndSend("", "oj.rating.update", body);
  org.springframework.amqp.core.Message failed=null;
  long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
  while(failed==null && System.nanoTime()<deadline) {failed=rabbit.receive(TagRatingMessagingConfiguration.FAILED_QUEUE);if(failed==null)Thread.sleep(100);}
  assertNotNull(failed,"failed event must be retained for inspection/replay");
  assertEquals(body,new String(failed.getBody(),java.nio.charset.StandardCharsets.UTF_8));
  assertEquals("TAG_RATING_PROCESSING_FAILED",failed.getMessageProperties().getHeader("failureReason"));
  assertFalse(failed.getMessageProperties().getHeaders().toString().contains("synthetic SQL"));
  verify(submissions,times(3)).findById(Long.MAX_VALUE);
 }
}
