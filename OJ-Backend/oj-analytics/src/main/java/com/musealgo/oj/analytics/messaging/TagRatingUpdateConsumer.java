package com.musealgo.oj.analytics.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.musealgo.oj.analytics.application.TagRatingService;
import com.musealgo.oj.api.submission.SubmissionJudgedEvent;
import com.musealgo.oj.api.submission.SubmissionQuery;

/**
 * 监听 submission.judged 事件，AC 时更新标签 ELO 评分。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "oj.messaging", name = "rabbitmq-enabled", havingValue = "true")
public class TagRatingUpdateConsumer {

    /** 判题模块通过该契约暴露提交上下文，禁止直接注入 SubmissionMapper */
    private final SubmissionQuery submissionQuery;
    private final TagRatingService tagRatingService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = "oj.rating.update", containerFactory = "tagRatingListenerFactory")
    public void handle(String payload) {
        SubmissionJudgedEvent event = null;
        try {
            event = objectMapper.readValue(payload, SubmissionJudgedEvent.class);
            var context = submissionQuery.findById(event.submissionId()).orElse(null);
            if (context == null || context.status() == null) {
                return;
            }
            // 仅 AC 触发评分更新
            if (context.status() != 1) {
                return;
            }
            tagRatingService.rebuildForUser(context.userId());
            log.info("TagRating 更新完成 submissionId={} userId={} problemId={}",
                    event.submissionId(), context.userId(), context.problemId());
        } catch (Exception e) {
            log.error("TagRating 提示处理失败 submissionId={}", event == null ? null : event.submissionId(), e);
            throw new IllegalStateException("标签评分提示处理失败", e);
        }
    }
}
