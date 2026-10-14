package top.everblue.oj.judge.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.everblue.oj.judge.entity.JudgeTask;
import top.everblue.oj.judge.mapper.JudgeTaskMapper;
import top.everblue.oj.judge.mapper.SubmissionMapper;
import top.everblue.oj.judge.service.JudgeTaskService;

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
            judgeTaskMapper.insert(task);
            log.info("【判题任务】创建成功 submissionId={}, taskId={}", submissionId, task.getId());
            return task;
        } catch (DuplicateKeyException e) {
            // 幂等：已存在，查回已有记录
            JudgeTask existing = judgeTaskMapper.selectOne(
                    new LambdaUpdateWrapper<JudgeTask>()
                            .eq(JudgeTask::getSubmissionId, submissionId));
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
            task.setAttemptCount(task.getAttemptCount() + 1);
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
    public boolean complete(Long taskId) {
        int rows = judgeTaskMapper.completeIfProcessing(taskId, LocalDateTime.now(), LocalDateTime.now());
        if (rows > 0) {
            log.info("【判题任务】完成 taskId={}", taskId);
            return true;
        }
        log.warn("【判题任务】完成被跳过 taskId={}（任务已不处于 PROCESSING，可能被看门狗重置）", taskId);
        return false;
    }

    // ---- 失败 + 重试 ----

    @Override
    public void fail(Long taskId, String errorMessage) {
        JudgeTask current = judgeTaskMapper.selectById(taskId);
        if (current == null) {
            log.warn("【判题任务】fail 时 taskId={} 不存在", taskId);
            return;
        }

        int nextAttempt = current.getAttemptCount(); // claim 时已 +1

        JudgeTask update = new JudgeTask();
        update.setId(taskId);
        update.setLastError(errorMessage);
        update.setUpdatedAt(LocalDateTime.now());

        if (nextAttempt >= MAX_ATTEMPTS) {
            // 超过最大重试次数 → 标记为永久失败
            update.setStatus(JudgeTask.STATUS_FAILED);
            update.setCompletedAt(LocalDateTime.now());
            log.warn("【判题任务】重试耗尽 taskId={}, attempts={}, 标记 FAILED",
                    taskId, nextAttempt);
            submissionMapper.updateJudgePhase(current.getSubmissionId(), "FAILED");
        } else {
            // 未达上限 → 重置为 PENDING，指数退避重试
            update.setStatus(JudgeTask.STATUS_PENDING);
            int backoffSeconds = BASE_BACKOFF_SECONDS * (1 << nextAttempt); // 30, 60, 120
            update.setScheduledAt(LocalDateTime.now().plusSeconds(backoffSeconds));
            update.setLockedAt(null); // 释放锁
            log.info("【判题任务】重试调度 taskId={}, attempt={}, backoff={}s",
                    taskId, nextAttempt, backoffSeconds);
        }

        judgeTaskMapper.updateById(update);
    }
}
