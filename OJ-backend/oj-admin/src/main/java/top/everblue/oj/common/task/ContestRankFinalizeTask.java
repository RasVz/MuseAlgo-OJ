package top.everblue.oj.common.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.everblue.oj.contest.entity.Contest;
import top.everblue.oj.contest.service.ContestRankService;
import top.everblue.oj.contest.service.ContestService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 比赛结束后的排行榜固化定时任务。
 * <p>
 * 存在这个任务的原因是「比赛结束」不是一个事件：进程可能在比赛中途重启、也可能赛事结束时间被临时改过，
 * 没有可靠的消息来触发固化。所以退化为<strong>轮询兜底</strong>——每 30 分钟扫一遍已过 endTime 的比赛，
 * 对尚未固化的执行一次，固化逻辑本身幂等（由 {@code hasFinalizedRank} 判重），因此重复执行无副作用。
 * </p>
 * <p>
 * 注意定时任务放在 oj-admin 而不是 oj-contest：启动类与调度开关统一由 oj-admin 的
 * {@link org.springframework.boot.autoconfigure.SpringBootApplication} 承载，
 * 业务模块内放 {@code @Scheduled} 会让模块无法单独验证行为。
 * </p>
 * <p>
 * 单场失败只记日志不中断循环：一场比赛的排名计算异常不应该让当轮所有其他比赛都无法固化，
 * 下一轮 30 分钟后会自然重试。
 * </p>
 */
@Slf4j
@Component
public class ContestRankFinalizeTask {

    @Autowired
    private ContestService contestService;

    @Autowired
    private ContestRankService contestRankService;

    /**
     * 每半小时检查一次已结束但尚未固化排行榜的比赛
     */
    @Scheduled(fixedDelay = 30 * 60000)
    public void finalizeEndedContests() {
        LocalDateTime now = LocalDateTime.now();

        List<Contest> endedContests = contestService.list(
                new LambdaQueryWrapper<Contest>()
                        .isNotNull(Contest::getEndTime)
                        .le(Contest::getEndTime, now)
        );

        if (endedContests == null || endedContests.isEmpty()) {
            return;
        }

        for (Contest contest : endedContests) {
            if (contest == null || contest.getId() == null) {
                continue;
            }

            boolean finalized = contestRankService.hasFinalizedRank(contest.getId());
            if (finalized) {
                continue;
            }

            try {
                contestRankService.finalizeContest(contest.getId());
            } catch (Exception e) {
                log.error("排名最终化失败", e);
            }
        }
    }
}