package com.musealgo.oj.contest.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.musealgo.oj.api.submission.*;
import com.musealgo.oj.contest.sse.ContestRankSsePublisher;

/** MQ only accelerates board invalidation; clients re-read authoritative GET snapshots. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix="oj.messaging",name="rabbitmq-enabled",havingValue="true")
public class ContestRankConsumer {
    static final String RANK_QUEUE="oj.rank.update";
    private static final ObjectMapper JSON=new ObjectMapper();
    private final ContestSubmissionQuery submissionQuery;
    private final ContestRankSsePublisher rankSsePublisher;
    @RabbitListener(queues=RANK_QUEUE,concurrency="1-3")
    public void onSubmissionJudged(String payload) {
        SubmissionJudgedEvent event;
        try {event=JSON.readValue(payload,SubmissionJudgedEvent.class);}
        catch(Exception e){log.warn("忽略无效排名事件");return;}
        if(event==null || event.submissionId()==null)return;
        submissionQuery.findById(event.submissionId()).filter(s->s.contestId()!=null && s.vpAttemptId()==null)
                .ifPresent(s->rankSsePublisher.officialAndGlobalChanged(s.contestId()));
    }
}
