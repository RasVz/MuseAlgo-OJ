package top.everblue.oj.contest.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import top.everblue.oj.common.exception.ForbiddenException;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.contest.entity.Contest;
import top.everblue.oj.contest.service.ContestService;
import top.everblue.oj.contest.sse.SseRegistry;

import java.time.LocalDateTime;

/**
 * SSE 直播端点 —— 比赛排行榜实时推送。
 * <p>
 * 本 Controller 只负责「建立连接」这一件事，真正的连接持有与广播在
 * {@link top.everblue.oj.contest.sse.SseRegistry}，推送触发点在消息消费者
 * （收到判题完成的 MQ 消息后）。理解这条链路要串起三处代码：
 * 本类建连 → 注册表存连接 → 消费者广播。
 * </p>
 *
 * <h3>OI 赛事的防泄漏闸门</h3>
 * <p>
 * OI 模式比赛按分数给分，<b>进行中看到实时榜等同于泄题</b>（能看到别人做到哪一题、
 * 花了多少时间，会直接影响后续策略），因此比赛进行中只允许教师与管理员看榜，
 * 其余用户直接 403。竞赛模式不受此限，赛后选手可以正常查看完整榜单。
 * </p>
 *
 * <h3>为什么超时传 0L</h3>
 * <p>
 * {@code new SseEmitter(0L)} 表示<b>不设服务端超时</b>。一场比赛可能连续数小时没有新提交，
 * 若沿用容器默认超时，长时间无推送的连接会被服务端主动关闭，前端 EventSource 重连时
 * 会短暂闪回「正在连接」。改用无限超时后，连接的存活完全由 TCP 与代理层决定，
 * 断开时由浏览器的 EventSource 自动重连。
 * </p>
 */
@RestController
@RequestMapping("/contest")
@RequiredArgsConstructor
public class ContestRankingSseController {

    private final SseRegistry sseRegistry;
    private final ContestService contestService;

    /**
     * 建立榜单直播连接（HTTP text/event-stream）。
     * <p>
     * 返回后连接常驻，调用方不再返回响应体；每条推送以 {@code event: ranking} 帧送达。
     * </p>
     *
     * @param contestId 比赛 ID
     * @return 已注册的 {@link SseEmitter}，由 Spring MVC 接管异步响应
     * @throws ForbiddenException OI 比赛进行中且调用者非教师/管理员时抛出
     */
    @GetMapping(value = "/{contestId}/ranking/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long contestId) {
        Contest contest = contestService.getById(contestId);
        if (isOiContestInProgress(contest) && !SecurityUtils.isTeacherOrAdmin()) {
            throw new ForbiddenException("OI 比赛进行中，排行榜暂不可见");
        }
        // 0L = 永不超时：比赛中可能长时间无推送，交给对端断开与浏览器重连兜底
        SseEmitter emitter = new SseEmitter(0L); // 无超时
        sseRegistry.register(contestId, emitter);
        // 发送初始连接确认
        try { emitter.send(SseEmitter.event().name("connected").data("ok")); } catch (Exception ignored) {}
        return emitter;
    }

    /**
     * 判断某场比赛是否处于「OI 模式且正在进行中」的时间窗内。
     * <p>
     * 时间边界取闭区间：{@code now == startTime} 与 {@code now == endTime} 均视为进行中。
     * 因为榜单是懒刷新（由判题消息触发），边界取开区间会让恰好在结束时刻提交的选手漏掉最后一条推送。
     * 比赛不存在或非 OI 模式一律返回 {@code false}（fail-open，避免误拦）。
     */
    private static boolean isOiContestInProgress(Contest contest) {
        if (contest == null || !"OI".equalsIgnoreCase(contest.getMode())) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        return contest.getStartTime() != null && contest.getEndTime() != null
                && !now.isBefore(contest.getStartTime()) && !now.isAfter(contest.getEndTime());
    }
}
