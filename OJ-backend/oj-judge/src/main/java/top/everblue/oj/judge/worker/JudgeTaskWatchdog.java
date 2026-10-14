package top.everblue.oj.judge.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.everblue.oj.judge.entity.JudgeTask;
import top.everblue.oj.judge.mapper.JudgeTaskMapper;
import top.everblue.oj.judge.service.JudgeTaskService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 判题任务看门狗：回收卡在 PROCESSING 状态的任务。
 *
 * <h3>为什么需要</h3>
 * Worker 在领取任务后、完成前崩溃（OOM / 容器被杀 / 判题机挂起），
 * 任务会永远停留在 PROCESSING，对应提交永远显示"评测中"。
 *
 * <h3>工作方式</h3>
 * <ol>
 *   <li>定时扫描 {@code status='PROCESSING' AND locked_at < deadline} 的任务</li>
 *   <li>对每条任务走 {@link JudgeTaskService#fail} 的标准流程：
 *       未达重试上限 → 重置 PENDING + 指数退避；达上限 → FAILED 并标记提交失败</li>
 * </ol>
 *
 * <h3>与正常 Worker 的竞态</h3>
 * 阈值默认 10 分钟，远大于正常判题耗时（逐用例 20s 超时 + Fast Fail），
 * 正常任务不会被误伤。极端情况下原 Worker 迟到完成时，
 * {@code completeIfProcessing} 条件更新会拒绝其写入，状态由看门狗路径接管。
 *
 * <h3>配置</h3>
 * <ul>
 *   <li>{@code oj.judge.watchdog.enabled}：总开关，默认 true</li>
 *   <li>{@code oj.judge.watchdog.interval-ms}：扫描间隔，默认 60000</li>
 *   <li>{@code oj.judge.watchdog.stuck-timeout-ms}：卡住判定阈值，默认 600000（10 分钟）</li>
 *   <li>{@code oj.judge.watchdog.batch-size}：单批次上限，默认 50</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "oj.judge.watchdog", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JudgeTaskWatchdog {

    private final JudgeTaskMapper judgeTaskMapper;
    private final JudgeTaskService judgeTaskService;

    @Value("${oj.judge.watchdog.stuck-timeout-ms:600000}")
    private long stuckTimeoutMs;

    @Value("${oj.judge.watchdog.batch-size:50}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${oj.judge.watchdog.interval-ms:60000}")
    public void rescueStuckTasks() {
        LocalDateTime deadline = LocalDateTime.now().minusNanos(stuckTimeoutMs * 1_000_000L);

        List<JudgeTask> stuckTasks;
        try {
            stuckTasks = judgeTaskMapper.selectStuckTasks(deadline, batchSize);
        } catch (Exception e) {
            log.error("【判题看门狗】扫描卡住任务失败: {}", e.getMessage());
            return;
        }

        if (stuckTasks.isEmpty()) {
            return;
        }

        log.warn("【判题看门狗】发现 {} 个卡住任务（PROCESSING 超过 {}ms），开始回收",
                stuckTasks.size(), stuckTimeoutMs);

        for (JudgeTask task : stuckTasks) {
            try {
                // 走标准 fail 流程：自动获得指数退避重试 / 超限 FAILED 语义
                judgeTaskService.fail(task.getId(),
                        "看门狗回收：任务卡在 PROCESSING 超过 " + stuckTimeoutMs + "ms（lockedAt=" + task.getLockedAt() + "）");
                log.warn("【判题看门狗】已回收 taskId={}, submissionId={}, attempt={}",
                        task.getId(), task.getSubmissionId(), task.getAttemptCount());
            } catch (Exception e) {
                log.error("【判题看门狗】回收失败 taskId={}: {}", task.getId(), e.getMessage());
            }
        }
    }
}
