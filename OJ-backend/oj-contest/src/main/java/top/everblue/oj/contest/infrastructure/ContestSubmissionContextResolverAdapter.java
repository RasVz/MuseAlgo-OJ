package top.everblue.oj.contest.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.contest.ContestSubmissionContextResolver;
import top.everblue.oj.contest.entity.Contest;
import top.everblue.oj.contest.entity.VpAttempt;
import top.everblue.oj.contest.mapper.ContestMapper;
import top.everblue.oj.contest.mapper.ProblemContestMapper;
import top.everblue.oj.contest.mapper.VpAttemptMapper;

import java.time.LocalDateTime;

/**
 * {@link ContestSubmissionContextResolver} 的 oj-contest 侧实现。
 * <p>
 * 提交请求进来时判定「这次提交属于正式比赛还是某次 VP」，返回带vpAttemptId 的上下文。
 * 这是全系统判定提交归属的<b>唯一权威出口</b>——因为 {@code submission.contestId} 与
 * {@code submission.vpAttemptId} 一旦写错，下游的榜单、封榜、成绩统计会全部跟着错。
 * </p>
 *
 * <h3>为什么用抛异常表达「不可提交」</h3>
 * <p>
 * 判定失败不是「返回空上下文」：空上下文会被下游当成普通提交继续处理，
 * 造成比赛时间外的提交混入榜单。抛异常让调用链立刻中断，
 * 由统一异常处理转成对用户有意义的提示。
 * </p>
 *
 * <h3>正式比赛优先于 VP</h3>
 * <p>
 * 判定顺序是「先看是否在正式赛程内，命中就直接返回不带 vpAttemptId 的上下文」，
 * 而非反过来。理由是：VP 只允许在正式比赛<b>结束之后</b>发起
 * （见 {@link top.everblue.oj.contest.service.impl.VpAttemptServiceImpl}），
 * 两者时间窗本不重叠；按正式优先判定后，即使将来放宽 VP 的时间限制，
 * 正式赛程内的提交也不会被误判成 VP 提交而绕开正式榜单。
 * </p>
 */
@Service
public class ContestSubmissionContextResolverAdapter implements ContestSubmissionContextResolver {

    private final ContestMapper contestMapper;
    private final ContestProblemAccessAdapter access;
    private final ProblemContestMapper problemContestMapper;
    private final VpAttemptMapper vpAttemptMapper;

    public ContestSubmissionContextResolverAdapter(ContestProblemAccessAdapter access, ContestMapper contestMapper,
                                                   ProblemContestMapper problemContestMapper,
                                                   VpAttemptMapper vpAttemptMapper) {
        this.access = access;
        this.contestMapper = contestMapper;
        this.problemContestMapper = problemContestMapper;
        this.vpAttemptMapper = vpAttemptMapper;
    }

    /**
     * 判定一次提交所处的比赛上下文。
     * <p>
     * 三步校验：比赛存在 → 题目确实属于该比赛 → 落在某个有效时间窗内。
     * 第二步不可省——否则用户可以对任意「本场比赛里出现过的题目」向任意比赛提交，
     * 污染别的比赛的榜单。
     * </p>
     *
     * @param contestId 目标比赛 ID
     * @param problemId 目标题目 ID，必须已被配置进该比赛
     * @param userId 提交者 ID，用于定位其进行中的 VP 会话
     * @return 上下文；正式赛程内提交时 {@code vpAttemptId} 为 {@code null}
     * @throws IllegalArgumentException 比赛不存在，或题目不属于该比赛时抛出
     * @throws IllegalStateException 既不在正式赛程、也没有进行中的 VP 会话时抛出
     */
    @Override
    public SubmissionContext resolve(Long contestId, Long problemId, Long userId) {
        Contest contest = contestMapper.selectById(contestId);
        if (contest == null) {
            throw new IllegalArgumentException("比赛不存在");
        }
        if (problemContestMapper.countByContestAndProblem(contestId, problemId) == 0) {
            throw new IllegalArgumentException("题目不属于该比赛");
        }

        if (!access.canReadContestProblem(contestId, problemId, userId))
            throw new top.everblue.oj.common.exception.ForbiddenException("无权向该比赛题目提交");
        LocalDateTime now = LocalDateTime.now();
        // 闭区间判定：恰好在开赛或结束时刻的提交应当被接受，
        // 否则开赛瞬间的提交会被拒、结束瞬间的提交会被判为超时提交
        if (contest.getStartTime() != null && contest.getEndTime() != null
                && !now.isBefore(contest.getStartTime()) && !now.isAfter(contest.getEndTime())) {
            // 正式赛程内：vpAttemptId 留空，标记这份提交计入正式榜单
            return new SubmissionContext(contestId, null);
        }

        // 不在正式赛程，则尝试匹配一个进行中的 VP 会话。
        // 时间条件必须同时约束 startTime 与 endTime：只查 status=RUNNING 会让已超时未交卷的
        // 僵尸会话继续接受提交，等于白白延长了作答时间
        VpAttempt attempt = vpAttemptMapper.selectOne(new LambdaQueryWrapper<VpAttempt>()
                .eq(VpAttempt::getContestId, contestId)
                .eq(VpAttempt::getUserId, userId)
                .eq(VpAttempt::getStatus, VpAttempt.STATUS_RUNNING)
                .le(VpAttempt::getStartTime, now)
                .ge(VpAttempt::getEndTime, now)
                .orderByDesc(VpAttempt::getId)
                // 按 id 倒序取最新一次会话：用户可能先后发起过多次 VP，
                // 应取当前活跃的那次。LIMIT 1 则用于兜住脏数据导致的多行，selectOne 不会因此抛异常
                .last("LIMIT 1"));
        if (attempt == null) {
            throw new IllegalStateException("当前不在比赛或虚拟赛作答时间内");
        }
        return new SubmissionContext(contestId, attempt.getId());
    }
}
