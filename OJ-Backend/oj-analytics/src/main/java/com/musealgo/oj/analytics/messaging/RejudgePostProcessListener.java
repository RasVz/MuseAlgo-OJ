package com.musealgo.oj.analytics.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import com.musealgo.oj.analytics.application.TagRatingService;
import com.musealgo.oj.analytics.mapper.ProfileSnapshotMapper;
import com.musealgo.oj.api.judge.RejudgePostProcessEvent;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * 重判完成后的派生数据重建监听器（T8）。
 * <p>
 * oj-judge 在「新判题结果 + rejudge_item 成功状态」同事务提交后发布
 * {@link RejudgePostProcessEvent}；本监听器在 <b>AFTER_COMMIT</b> 阶段执行，保证：
 * <ul>
 *   <li>读到的是已提交的新结果，不会拿到重判事务的中间态；</li>
 *   <li>副作用失败时重判结果已经落库，不会被回滚。</li>
 * </ul>
 * 两段副作用彼此独立、各自 try/catch 只记日志，互不阻塞：
 * <ol>
 *   <li><b>标签 ELO 重建</b>：user_tag_rating 是按 AC 事件顺序累计的派生缓存，
 *       提交结论变化后必须整用户按历史顺序重放（见
 *       {@link TagRatingService#rebuildForUser(Long)}，内部 REQUIRES_NEW）。</li>
 *   <li><b>画像快照标脏</b>：提交所在周（周一为周期起点）的快照派生值已过期，
 *       置 stale=1 让报告/画像读取方跳过它；重建由 T8-2 的对账任务完成。
 *       本段用独立 REQUIRES_NEW 事务（见 {@link #markProfileSnapshotStale}）。</li>
 * </ol>
 * <p>
 * <b>为什么两段都要独立新事务</b>：AFTER_COMMIT 回调在原事务 commit 之后、资源清理之前执行，
 * 数据源连接仍绑定在当前线程上；此时用默认 REQUIRED 会「加入」已提交的事务，后续 SQL 不再有
 * commit 机会（Spring 文档明确要求从 afterCommit 触发的事务使用 REQUIRES_NEW）。否则
 * 副作用看起来执行成功、实际连接归还连接池时被回滚，派生数据静默丢失。
 * </p>
 * <p>
 * <b>已知边界（T8-1 刻意不做）</b>：这里没有重试、没有补偿/对账，失败只留下 error 日志；
 * T8-2 将补重试与基于 rejudge_operation/rejudge_item 的对账，届时本类只负责触发。
 * </p>
 * <p>
 * {@code fallbackExecution = true}：事件由 oj-judge 在事务内发布时走标准 AFTER_COMMIT；
 * 若将来发布方在没有事务上下文时发布（例如脚本/管理端手工补发），监听器会立即执行，
 * 而不是被 Spring 静默丢弃导致派生数据永久不重建。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RejudgePostProcessListener {

    private final TagRatingService tagRatingService;
    private final ProfileSnapshotMapper profileSnapshotMapper;
    private final PlatformTransactionManager transactionManager;

    /** 重判成功提交后的派生数据重建入口（两段独立失败隔离）。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRejudgePostProcess(RejudgePostProcessEvent event) {
        if (event == null) {
            log.warn("RejudgePostProcessEvent 为 null，跳过派生数据重建");
            return;
        }
        rebuildTagRating(event);
        markProfileSnapshotStale(event);
    }

    /** 第一段：按 AC 历史顺序重建该用户的标签 ELO。 */
    private void rebuildTagRating(RejudgePostProcessEvent event) {
        try {
            tagRatingService.rebuildForUser(event.userId());
            log.info("重判后标签 ELO 重建完成 operationId={} itemId={} submissionId={} userId={}",
                    event.operationId(), event.itemId(), event.submissionId(), event.userId());
        } catch (Exception e) {
            // 不得向上抛：AFTER_COMMIT 阶段异常无法回滚已提交的重判结果，只会让调用方误判失败
            log.error("重判后标签 ELO 重建失败（待 T8-2 重试/对账）" +
                            " operationId={} itemId={} submissionId={} userId={}: {}",
                    event.operationId(), event.itemId(), event.submissionId(), event.userId(),
                    e.getMessage(), e);
        }
    }

    /** 第二段：把原提交所在周（周一~周日）的画像快照标记为过期。 */
    private void markProfileSnapshotStale(RejudgePostProcessEvent event) {
        if (event.userId() == null || event.submissionTime() == null) {
            log.warn("重判事件缺少 userId/submissionTime，无法定位画像周期，跳过标脏" +
                            " operationId={} itemId={} submissionId={}",
                    event.operationId(), event.itemId(), event.submissionId());
            return;
        }
        LocalDate weekStart = weekStartOf(event.submissionTime().toLocalDate());
        try {
            // REQUIRES_NEW：AFTER_COMMIT 阶段原事务已提交但连接仍绑定，必须开独立事务，
            // 否则 UPDATE 不会提交（见类注释）。与 ELO 重建各自独立，任一段失败不影响另一段。
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            Integer updated = tx.execute(status ->
                    profileSnapshotMapper.markStaleForPeriod(event.userId(), weekStart));
            log.info("重判后画像快照标脏完成 operationId={} studentId={} periodStart={} updated={}",
                    event.operationId(), event.userId(), weekStart, updated);
        } catch (Exception e) {
            log.error("重判后画像快照标脏失败（待 T8-2 重试/对账）" +
                            " operationId={} itemId={} userId={} periodStart={}: {}",
                    event.operationId(), event.itemId(), event.userId(), weekStart,
                    e.getMessage(), e);
        }
    }

    /**
     * 提交时间所在统计周期的起点（周一）。
     * <p>与周报任务、报告周期口径一致：周期为 [周一, 周日]，周期开始日 = previousOrSame(MONDAY)。</p>
     */
    static LocalDate weekStartOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
