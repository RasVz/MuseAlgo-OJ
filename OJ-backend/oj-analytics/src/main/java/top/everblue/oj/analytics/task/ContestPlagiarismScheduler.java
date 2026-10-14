package top.everblue.oj.analytics.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.everblue.oj.analytics.application.PlagiarismService;
import top.everblue.oj.api.contest.ContestPlagiarismCommand;
import top.everblue.oj.api.contest.ContestScheduleQuery;

import java.util.Set;

/**
 * 赛后自动查重调度器（查重 M2）。
 * <p>
 * 每 5 分钟扫描「已结束 ≥ 10 分钟且未查重」的比赛，自动跑完全部题目的
 * 跨班比对，完成后经 {@link ContestPlagiarismCommand} 契约标记已查重，
 * 避免重复执行。比赛结束后的宽限期（10 分钟）等提交稳定落库。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContestPlagiarismScheduler {

    /** 比赛结束后等待分钟数（等提交稳定、榜单落定） */
    private static final int GRACE_MINUTES = 10;
    /** 每轮扫描间隔（毫秒） */
    private static final long SCAN_INTERVAL_MS = 5 * 60 * 1000L;

    private final ContestScheduleQuery contestScheduleQuery;
    private final ContestPlagiarismCommand contestPlagiarismCommand;
    private final PlagiarismService plagiarismService;

    @Scheduled(fixedDelay = SCAN_INTERVAL_MS)
    public void scanEndedContests() {
        Set<Long> contestIds;
        try {
            contestIds = contestScheduleQuery.findEndedUncheckedContestIds(GRACE_MINUTES);
        } catch (Exception e) {
            log.error("【赛后查重】扫描待查重比赛失败: {}", e.getMessage());
            return;
        }
        if (contestIds.isEmpty()) {
            return;
        }
        log.info("【赛后查重】发现 {} 场比赛待自动查重: {}", contestIds.size(), contestIds);
        for (Long contestId : contestIds) {
            try {
                int inserted = plagiarismService.runContestPlagiarismCheckInternal(contestId);
                contestPlagiarismCommand.markContestChecked(contestId);
                log.info("【赛后查重】比赛 {} 自动查重完成，新增报告 {}", contestId, inserted);
            } catch (Exception e) {
                // 单场失败不阻塞其他比赛；下一轮扫描会重试（未标记 = 未完成）
                log.error("【赛后查重】比赛 {} 自动查重失败，下轮重试: {}", contestId, e.getMessage());
            }
        }
    }
}
