package com.musealgo.oj.api.report;

import java.util.List;
import java.util.Set;

/**
 * 报告生命周期事务使用的通知意图写端口。
 * <p>实现只写通知域持有的持久化意图，不发送站内通知或外部消息。</p>
 */
public interface ReportNotificationPort {

    /**
     * 在报告成功 finalizer 事务内登记 REPORT_READY 意图。
     * 实现必须以 {@code Propagation.MANDATORY} 加入调用事务。
     *
     * @param command 当前 job、报告主体、代次和终态修订
     */
    void stageReadyIntent(ReportIntentCommand command);

    /**
     * 在报告失败 finalizer 事务内登记 REPORT_FAILED 意图。
     * 实现必须以 {@code Propagation.MANDATORY} 加入调用事务。若
     * {@code command.recipientId()} 为 null，表示历史报告主体缺失；实现须以
     * {@code REPORT_FAILED:{jobId}:{generationNo}:{terminalRevision}:UNASSIGNED}
     * 为 event key 保存 {@code SKIPPED_PERMISSION}，不得尝试发送。
     *
     * @param command 当前 job、报告主体、代次、终态修订和安全错误码
     */
    void stageFailedIntent(ReportIntentCommand command);

    /**
     * 显式 retry 时取代旧失败修订所有尚未通知的意图。
     * 实现必须先由调用方锁定 report_job，再锁定 notice intent，并以
     * {@code Propagation.MANDATORY} 加入 retry 事务；将旧修订中
     * {@code notification_id IS NULL} 且状态为 PENDING、DISPATCHING 或 FAILED
     * 的意图置为 SKIPPED_SUPERSEDED 并清除 dispatch lease。DELIVERED 与已有
     * SKIPPED 状态保持不变。
     *
     * @param jobId 报告 job ID
     * @param terminalRevision 被取代的旧失败修订
     */
    void supersedePendingFailureIntents(Long jobId, Long terminalRevision);

    /**
     * 在 report retention 事务中清理候选 job 的通知域终态数据。
     * 实现必须以 {@code Propagation.MANDATORY} 加入调用事务，仅删除自身拥有的
     * intent 及其关联站内通知；对超过留存期终态 job 的活动或未来待重试意图，
     * 先置为 SKIPPED_SUPERSEDED 并清除 lease，再同事务删除，避免无限阻塞留存。
     * 调用方已选出过期终态 job，并按 report_job → notice intent 顺序持锁。
     *
     * @param candidateJobIds 已超过留存期且由报告域选出的 job ID
     * @return notice 数据已清理、报告域可在同一事务删除的 job ID 集合
     */
    Set<Long> deleteTerminalIntentsForExpiredJobs(List<Long> candidateJobIds);
}
