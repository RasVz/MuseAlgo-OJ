package top.everblue.oj.common.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.everblue.oj.problem.service.ExamPaperService;

/**
 * 训练试卷作答会话到期自动交卷（兜底）。
 * <p>
 * 前端倒计时归零会主动交卷；本任务每分钟扫描一次兜底，
 * 保证即使客户端断开，过期会话也会被结算（客观 + 编程合并总分）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExamAttemptTimeoutTask {

    private final ExamPaperService examPaperService;

    @Scheduled(fixedDelay = 60_000)
    public void autoSubmitExpiredAttempts() {
        try {
            examPaperService.autoFinalizeExpired();
        } catch (Exception e) {
            log.warn("【训练会话】自动交卷任务异常: {}", e.getMessage());
        }
    }
}
