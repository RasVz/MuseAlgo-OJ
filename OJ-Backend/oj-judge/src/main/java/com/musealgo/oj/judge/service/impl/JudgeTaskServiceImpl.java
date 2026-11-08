package com.musealgo.oj.judge.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.musealgo.oj.judge.application.RejudgeResultCodec;
import com.musealgo.oj.judge.entity.JudgeTask;
import com.musealgo.oj.judge.entity.RejudgeItem;
import com.musealgo.oj.judge.entity.RejudgeOperation;
import com.musealgo.oj.judge.entity.Submission;
import com.musealgo.oj.judge.mapper.JudgeTaskMapper;
import com.musealgo.oj.judge.mapper.RejudgeItemMapper;
import com.musealgo.oj.judge.mapper.RejudgeOperationMapper;
import com.musealgo.oj.judge.mapper.SubmissionMapper;
import com.musealgo.oj.judge.service.JudgeTaskService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 判题任务服务实现。
 *
 * <h3>幂等设计</h3>
 * {@link #submit(Long)} 利用数据库 uk_judge_task_submission 唯一约束：
 * INSERT 冲突时捕获 DuplicateKeyException 回查已有记录。
 *
 * <h3>原子批量领取</h3>
 * {@link #claimBatch(int)} 在 @Transactional 中执行：
 * <ol>
 *   <li>SELECT ... FOR UPDATE SKIP LOCKED 批量锁定（LIMIT N）</li>
 *   <li>UPDATE status='PROCESSING', locked_at=NOW(), attempt_count+1</li>
 * </ol>
 * 同一事务内完成，保证并发安全；批量领取让单轮轮询可并行填充判题线程池（A2）。
 *
 * <h3>重试策略</h3>
 * {@link #fail(Long, String)} 采用指数退避：
 * attempt_count=0 → 30s 后重试，attempt_count=1 → 60s，attempt_count=2 → 120s。
 * 超过 3 次标记 FAILED，需要人工介入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JudgeTaskServiceImpl implements JudgeTaskService {

    private final JudgeTaskMapper judgeTaskMapper;
    private final SubmissionMapper submissionMapper;
    private final RejudgeItemMapper rejudgeItemMapper;
    private final RejudgeOperationMapper rejudgeOperationMapper;

    private static final int MAX_ATTEMPTS = 3;
    private static final int BASE_BACKOFF_SECONDS = 30;

    // ---- 幂等提交 ----

    @Override
    public JudgeTask submit(Long submissionId) {
        JudgeTask task = new JudgeTask();
        task.setSubmissionId(submissionId);
        task.setStatus(JudgeTask.STATUS_PENDING);
        task.setAttemptCount(0);
        task.setScheduledAt(LocalDateTime.now());
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());

        try {
            if (judgeTaskMapper.insert(task) != 1) throw new IllegalStateException("判题任务保存失败");
            log.info("【判题任务】创建成功 submissionId={}, taskId={}", submissionId, task.getId());
            return task;
        } catch (DuplicateKeyException e) {
            // 幂等：已存在，查回已有记录
            JudgeTask existing = judgeTaskMapper.selectOne(
                    new LambdaUpdateWrapper<JudgeTask>()
                            .eq(JudgeTask::getSubmissionId, submissionId));
            if (existing == null) throw new IllegalStateException("判题任务冲突后未找到记录", e);
            log.info("【判题任务】幂等跳过 submissionId={}, 已有 taskId={}",
                    submissionId, existing != null ? existing.getId() : null);
            return existing;
        }
    }

    // ---- 原子批量领取 ----

    @Override
    @Transactional
    public List<JudgeTask> claimBatch(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        // Step 1: SELECT ... FOR UPDATE SKIP LOCKED → 锁定至多 limit 行
        List<JudgeTask> tasks = judgeTaskMapper.claimTasks(limit);

        if (tasks.isEmpty()) {
            return List.of();
        }

        // Step 2: UPDATE → 标记为处理中（同一事务内，锁未释放）
        LocalDateTime now = LocalDateTime.now();
        List<JudgeTask> claimed = new ArrayList<>(tasks.size());
        for (JudgeTask task : tasks) {
            task.setStatus(JudgeTask.STATUS_PROCESSING);
            task.setLockedAt(now);
            // 执行代次只在领取时递增；attempt_count 是失败重试预算，不在这里动。
            task.setGeneration(task.getGeneration() == null ? 1 : task.getGeneration() + 1);
            task.setUpdatedAt(now);
            judgeTaskMapper.updateById(task);
            claimed.add(task);
        }

        log.info("【判题任务】批量领取成功 count={}, submissionIds={}",
                claimed.size(), claimed.stream().map(JudgeTask::getSubmissionId).toList());
        return claimed;
    }

    // ---- 完成 ----

    @Override
    public boolean complete(JudgeTask task) {
        Long taskId = task.getId();
        int rows = judgeTaskMapper.completeIfProcessing(taskId, task.getGeneration(), LocalDateTime.now(), LocalDateTime.now());
        if (rows > 0) {
            log.info("【判题任务】完成 taskId={}", taskId);
            return true;
        }
        log.warn("【判题任务】完成被跳过 taskId={}（任务已不处于 PROCESSING，可能被看门狗重置）", taskId);
        return false;
    }

    // ---- 失败 + 重试 ----

    @Override
    @Transactional
    public void fail(JudgeTask task, String errorMessage) {
        int attempt = task.getAttemptCount() == null ? 0 : task.getAttemptCount();
        int generation = task.getGeneration() == null ? 0 : task.getGeneration();
        int nextAttempt = attempt + 1;
        boolean exhausted = nextAttempt >= MAX_ATTEMPTS;
        LocalDateTime now = LocalDateTime.now();
        var update = new LambdaUpdateWrapper<JudgeTask>()
                .eq(JudgeTask::getId, task.getId())
                .eq(JudgeTask::getStatus, JudgeTask.STATUS_PROCESSING)
                .eq(JudgeTask::getGeneration, generation)
                .set(JudgeTask::getLastError, errorMessage)
                .set(JudgeTask::getUpdatedAt, now)
                .set(JudgeTask::getLockedAt, null)
                .set(JudgeTask::getAttemptCount, nextAttempt)
                .set(JudgeTask::getStatus, exhausted ? JudgeTask.STATUS_FAILED : JudgeTask.STATUS_PENDING);
        if (exhausted) {
            update.set(JudgeTask::getCompletedAt, now);
        } else {
            update.set(JudgeTask::getScheduledAt,
                    now.plusSeconds(BASE_BACKOFF_SECONDS * (1L << Math.min(attempt, 10))));
        }
        int changed = judgeTaskMapper.update(null, update);
        if (changed == 0) {
            log.info("忽略过期判题执行的失败 taskId={}, generation={}", task.getId(), generation);
            return;
        }
        if (exhausted) {
            handleExhaustedFailure(task, errorMessage, now);
        }
    }

    /**
     * 重试预算耗尽：普通提交标记 judgePhase=FAILED；有未结束重判条目时恢复重判前结果，
     * 避免一条失败的重判把用户原有有效成绩清空。
     */
    private void handleExhaustedFailure(JudgeTask task, String errorMessage, LocalDateTime now) {
        RejudgeItem item = rejudgeItemMapper.selectOpenBySubmissionId(task.getSubmissionId());
        if (item == null) {
            submissionMapper.updateJudgePhase(task.getSubmissionId(), "FAILED");
            return;
        }
        Submission submission = submissionMapper.selectById(task.getSubmissionId());
        if (submission != null) {
            RejudgeResultCodec.restore(submission, item.getOldResultJson());
            submission.setUpdateTime(now);
            submissionMapper.updateById(submission);
        }
        item.setStatus(RejudgeItem.STATUS_FAILED);
        item.setFinishedAt(now);
        item.setErrorMessage(truncate(errorMessage, 1000));
        if (rejudgeItemMapper.updateById(item) != 1) throw new IllegalStateException("重判条目结果保存失败");

        RejudgeOperation operation = rejudgeOperationMapper.selectForUpdate(item.getOperationId());
        if (operation != null) {
            int failed = (operation.getFailedItems() == null ? 0 : operation.getFailedItems()) + 1;
            int succeeded = operation.getSucceededItems() == null ? 0 : operation.getSucceededItems();
            int total = operation.getTotalItems() == null ? 0 : operation.getTotalItems();
            operation.setFailedItems(failed);
            if (succeeded + failed >= total) {
                operation.setStatus(succeeded > 0 ? RejudgeOperation.STATUS_PARTIAL : RejudgeOperation.STATUS_FAILED);
                operation.setFinishedAt(now);
            }
            if (rejudgeOperationMapper.updateById(operation) != 1) throw new IllegalStateException("重判操作进度保存失败");
        }
        log.warn("【T8 重判】失败并恢复旧结果 submissionId={}, operationId={}, reason={}",
                task.getSubmissionId(), item.getOperationId(), errorMessage);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
