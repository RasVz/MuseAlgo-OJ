package top.everblue.oj.judge.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.everblue.oj.api.submission.SubmissionJudgedEvent;
import top.everblue.oj.judge.entity.JudgeTask;
import top.everblue.oj.judge.entity.OutboxEvent;
import top.everblue.oj.judge.mapper.OutboxEventMapper;
import top.everblue.oj.judge.service.JudgeTaskService;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 判题完成 + Outbox 事件写入的事务性包装。
 * <p>
 * 必须独立为 Service，因为 Spring AOP 的 @Transactional 只对
 * 外部调用（通过代理）生效，类内部自调用不会触发事务代理。
 * JudgeWorker 通过注入本 Service 来保证 complete + outbox insert 在同一事务中。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JudgeCompletionService {

    private final JudgeTaskService judgeTaskService;
    private final OutboxEventMapper outboxEventMapper;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 同一事务内：完成判题任务 + 写入 Outbox 事件。
     * <p>
     * complete 是条件更新：若任务已被看门狗重置（原 Worker 迟到完成），
     * 则放弃写 Outbox，避免同一提交发布两次 submission.judged 事件。
     * </p>
     */
    @Transactional
    public void completeWithOutbox(JudgeTask task) {
        boolean completed = judgeTaskService.complete(task.getId());
        if (!completed) {
            log.warn("【判题Worker】任务已被其他路径迁移，跳过 Outbox 写入 taskId={}", task.getId());
            return;
        }

        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID().toString());
        event.setAggregateType(OutboxEvent.AGGREGATE_SUBMISSION);
        event.setAggregateId(task.getSubmissionId());
        event.setEventType(OutboxEvent.EVENT_SUBMISSION_JUDGED);
        event.setPayload(buildPayload(task));
        event.setStatus(OutboxEvent.STATUS_PENDING);
        event.setRetryCount(0);
        event.setNextAttemptAt(LocalDateTime.now());
        event.setCreatedAt(LocalDateTime.now());
        outboxEventMapper.insert(event);

        log.info("【判题Worker】Outbox 事件已写入 eventId={}, submissionId={}",
                event.getId(), task.getSubmissionId());
    }

    private String buildPayload(JudgeTask task) {
        try {
            var event = new SubmissionJudgedEvent(
                    task.getSubmissionId(),
                    task.getId(),
                    LocalDateTime.now().toString()
            );
            return OBJECT_MAPPER.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.warn("【判题Worker】SubmissionJudgedEvent 序列化失败: {}", e.getMessage());
            return String.format(
                    "{\"submissionId\":%d,\"taskId\":%d,\"judgedAt\":\"%s\"}",
                    task.getSubmissionId(), task.getId(), LocalDateTime.now());
        }
    }
}
