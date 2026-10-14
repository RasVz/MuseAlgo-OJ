package top.everblue.oj.judge.service;

import top.everblue.oj.judge.entity.JudgeTask;

import java.util.List;

/**
 * 判题任务服务。
 */
public interface JudgeTaskService {

    /**
     * 幂等提交判题任务。
     * <p>
     * 利用 judge_task 表的 uk_judge_task_submission 唯一约束，
     * 重复提交自动忽略，返回已有的任务记录。
     * </p>
     *
     * @param submissionId 提交 ID
     * @return 新创建或已存在的任务
     */
    JudgeTask submit(Long submissionId);

    /**
     * 原子批量领取待处理任务（FOR UPDATE SKIP LOCKED，高并发方案 A2）。
     *
     * @param limit 单批上限，与判题线程池容量对齐
     * @return 被领取的任务列表，无待处理任务时为空列表
     */
    List<JudgeTask> claimBatch(int limit);

    /**
     * 标记任务完成（条件更新）。
     * <p>
     * 仅当任务仍处于 PROCESSING 时才生效；若任务已被看门狗重置或标记失败，
     * 返回 false，调用方应放弃后续动作（如写 Outbox 事件），避免重复发布。
     * </p>
     *
     * @return true = 完成状态写入成功；false = 任务状态已被其他路径迁移
     */
    boolean complete(Long taskId);

    /**
     * 标记任务失败。
     * <p>
     * 如果 attempt_count 未达上限 → 重置为 PENDING + 指数退避重试。<br>
     * 如果已达上限（≥3）→ 标记为 FAILED，不再重试。
     * </p>
     */
    void fail(Long taskId, String errorMessage);
}
