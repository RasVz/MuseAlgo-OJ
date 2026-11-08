package com.musealgo.oj.contest.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import com.musealgo.oj.api.judge.RejudgePostProcessEvent;
import com.musealgo.oj.contest.service.VpAttemptService;

/**
 * T8-2b：重判 VP 提交后重算对应 VP 会话成绩。
 * <p>
 * 监听 oj-judge 在重判结果提交后发布的 {@link RejudgePostProcessEvent}；
 * 只处理带 vpAttemptId 的条目，正式比赛提交由其他监听器处理。
 * 失败只记录日志，不反向影响重判结果（T8-3 可加对账）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RejudgeVpRecalculationListener {

    private final VpAttemptService vpAttemptService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRejudgePostProcess(RejudgePostProcessEvent event) {
        if (event == null || event.vpAttemptId() == null) {
            return;
        }
        try {
            vpAttemptService.recalculateForRejudge(event.vpAttemptId());
        } catch (Exception e) {
            log.error("【VP】重判后重算失败 vpAttemptId={}, submissionId={}: {}",
                    event.vpAttemptId(), event.submissionId(), e.getMessage(), e);
        }
    }
}
