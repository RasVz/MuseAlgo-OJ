package top.everblue.oj.contest.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import top.everblue.oj.api.submission.*;
import top.everblue.oj.contest.service.ContestRankService;
import top.everblue.oj.contest.sse.SseRegistry;

/** MQ 只唤醒快照广播；读取与结算不依赖消息是否送达。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix="oj.messaging",name="rabbitmq-enabled",havingValue="true")
public class ContestRankConsumer {
    static final String RANK_QUEUE="oj.rank.update";
    private static final ObjectMapper JSON=new ObjectMapper();
    private final ContestSubmissionQuery submissionQuery;
    private final ContestRankService ranks;
    private final SseRegistry sse;
    @RabbitListener(queues=RANK_QUEUE,concurrency="1-3")
    public void onSubmissionJudged(String payload) {
        SubmissionJudgedEvent event;
        try {event=JSON.readValue(payload,SubmissionJudgedEvent.class);}
        catch(Exception e){log.warn("忽略无效排名事件");return;}
        if(event==null || event.submissionId()==null)return;
        submissionQuery.findById(event.submissionId()).filter(s->s.contestId()!=null && s.vpAttemptId()==null)
                .ifPresent(s->sse.broadcast(s.contestId(),ranks.getRealtimeTopN(s.contestId(),Integer.MAX_VALUE)));
    }
}
