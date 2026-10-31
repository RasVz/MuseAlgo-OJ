package com.musealgo.oj.judge.worker;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.musealgo.oj.api.judge.RejudgePostProcessEvent;
import com.musealgo.oj.api.submission.SubmissionJudgedEvent;
import com.musealgo.oj.api.user.UserStatsCommand;
import com.musealgo.oj.judge.application.RejudgeResultCodec;
import com.musealgo.oj.judge.entity.JudgeTask;
import com.musealgo.oj.judge.entity.OutboxEvent;
import com.musealgo.oj.judge.entity.RejudgeItem;
import com.musealgo.oj.judge.entity.RejudgeOperation;
import com.musealgo.oj.judge.entity.Submission;
import com.musealgo.oj.judge.mapper.OutboxEventMapper;
import com.musealgo.oj.judge.mapper.RejudgeItemMapper;
import com.musealgo.oj.judge.mapper.RejudgeOperationMapper;
import com.musealgo.oj.judge.service.JudgeTaskService;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 判题完成 + Outbox 事件写入的事务性包装。
 * <p>
 * 必须独立为 Service，因为 Spring AOP 的 @Transactional 只对
 * 外部调用（通过代理）生效，类内部自调用不会触发事务代理。
 * JudgeOrchestrator通过本Service将结果、任务完成和Outbox原子提交。
 * </p>
 * <p>
 * T8：如果该提交存在未结束的 rejudge_item，则在同事务内回写条目/操作状态，
 * 重算账号计数并发布 {@link RejudgePostProcessEvent}（监听方重建 ELO / 标记快照过期）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JudgeCompletionService {

    private final JudgeTaskService judgeTaskService;
    private final OutboxEventMapper outboxEventMapper;
    private final com.musealgo.oj.judge.mapper.SubmissionMapper submissionMapper;
    private final com.musealgo.oj.judge.mapper.SubmissionResultMapper resultMapper;
    private final RejudgeItemMapper rejudgeItemMapper;
    private final RejudgeOperationMapper rejudgeOperationMapper;
    private final ApplicationEventPublisher eventPublisher;
    /** 用户计数写契约：首次 AC / 重判后维护 accepted_count */
    private final UserStatsCommand userStatsCommand;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 同一事务内：完成判题任务 + 写结果 + 写 Outbox 事件 + 重判条目回写。
     * <p>
     * complete 是条件更新：若任务已被看门狗重置（原 Worker 迟到完成），
     * 则放弃写 Outbox，避免同一提交发布两次 submission.judged 事件。
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void completeWithOutbox(JudgeTask task, Submission submission,
                                   boolean persistNormalizedResults) {
        boolean completed = judgeTaskService.complete(task);
        if (!completed) throw new StaleJudgeExecutionException();
        // 条件完成先锁定task行；失败/重领均不能越过该锁。以下写入与完成和事件同事务。
        if (submissionMapper.updateById(submission) != 1) throw new IllegalStateException("提交结果写入失败");

        LocalDateTime now = LocalDateTime.now();
        RejudgeItem rejudgeItem = rejudgeItemMapper.selectOpenBySubmissionId(submission.getId());
        if (rejudgeItem != null) {
            finishRejudge(rejudgeItem, submission, now);
        } else {
            syncAcceptedProblemCount(submission);
        }

        if (persistNormalizedResults) {
            var result = new com.musealgo.oj.judge.entity.SubmissionResult();
            result.setSubmissionId(submission.getId()); result.setStatus(submission.getStatus());
            result.setScore(submission.getScore()); result.setRunTime(submission.getRunTime());
            result.setRunMemory(submission.getRunMemory()); result.setErrorMessage(submission.getErrorMessage());
            result.setJudgedAt(LocalDateTime.now());
            resultMapper.upsert(result);
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

    /**
     * 普通提交（非重判）的“通过题目数”增量维护。
     * <p>
     * 首次 AC 才累计（accepted_count = 不同题目数）。这里用「有没有更早 AC」判断；
     * 同一用户并发 AC 同一题的极端情况可能多计一次，T8 重判/计数重算会按提交表恢复。
     * </p>
     */
    private void syncAcceptedProblemCount(Submission submission) {
        if (submission.getStatus() != null && submission.getStatus() == 1
                && submission.getUserId() != null && submission.getProblemId() != null) {
            Long earlierAccepted = submissionMapper.selectCount(new LambdaQueryWrapper<Submission>()
                    .eq(Submission::getUserId, submission.getUserId())
                    .eq(Submission::getProblemId, submission.getProblemId())
                    .eq(Submission::getStatus, (byte) 1)
                    .lt(Submission::getId, submission.getId()));
            if (earlierAccepted == null || earlierAccepted == 0) {
                userStatsCommand.incrementAcceptedProblemCount(submission.getUserId());
            }
        }
    }

    /** 回写重判条目/操作，并发出派生数据重建事件（AFTER_COMMIT 监听）。 */
    private void finishRejudge(RejudgeItem item, Submission submission, LocalDateTime now) {
        item.setNewResultJson(RejudgeResultCodec.toJson(submission));
        item.setStatus(RejudgeItem.STATUS_SUCCEEDED);
        item.setFinishedAt(now);
        item.setErrorMessage(null);
        rejudgeItemMapper.updateById(item);

        RejudgeOperation operation = rejudgeOperationMapper.selectById(item.getOperationId());
        if (operation != null) {
            int succeeded = nonNull(operation.getSucceededItems()) + 1;
            int failed = nonNull(operation.getFailedItems());
            int total = nonNull(operation.getTotalItems());
            operation.setSucceededItems(succeeded);
            if (succeeded + failed >= total) {
                operation.setStatus(RejudgeOperation.STATUS_COMPLETED);
                operation.setFinishedAt(now);
            }
            rejudgeOperationMapper.updateById(operation);
        }

        // 重判可能把 AC 改成 WA 或反之，缓存列必须按提交表全量重算（幂等）。
        // 身份字段优先取条目快照/持久化后的提交：判题编排传入的实体可能只带结果字段。
        Submission persisted = submissionMapper.selectById(submission.getId());
        Long userId = item.getUserId() != null ? item.getUserId()
                : (persisted != null ? persisted.getUserId() : submission.getUserId());
        Long problemId = item.getProblemId() != null ? item.getProblemId()
                : (persisted != null ? persisted.getProblemId() : submission.getProblemId());
        if (userId != null) {
            userStatsCommand.recalculateCounters(userId);
        }
        LocalDateTime submissionTime = persisted != null ? persisted.getCreateTime() : submission.getCreateTime();

        // 在事务内发布；@TransactionalEventListener(AFTER_COMMIT) 在提交后执行。
        eventPublisher.publishEvent(new RejudgePostProcessEvent(
                item.getOperationId(), item.getId(), submission.getId(),
                userId, problemId, submissionTime, item.getVpAttemptId()));
        log.info("【T8 重判】完成 submissionId={}, operationId={}, status={}, score={}",
                submission.getId(), item.getOperationId(), submission.getStatus(), submission.getScore());
    }

    private static int nonNull(Integer value) {
        return value == null ? 0 : value;
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
