package com.musealgo.oj.contest.infrastructure;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.contest.error.ContestErrorCode;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import com.musealgo.oj.api.contest.ContestSubmissionContextResolver;
import com.musealgo.oj.contest.entity.Contest;
import com.musealgo.oj.contest.entity.ContestUser;
import com.musealgo.oj.contest.entity.VpAttempt;
import com.musealgo.oj.contest.service.ContestUserService;
import com.musealgo.oj.contest.service.impl.ContestUserServiceImpl;
import com.musealgo.oj.contest.mapper.ContestMapper;
import com.musealgo.oj.contest.mapper.ProblemContestMapper;
import com.musealgo.oj.contest.mapper.VpAttemptMapper;

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
 * （见 {@link com.musealgo.oj.contest.service.impl.VpAttemptServiceImpl}），
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
    private final ContestUserService contestUserService;

    public ContestSubmissionContextResolverAdapter(ContestProblemAccessAdapter access, ContestMapper contestMapper,
                                                   ProblemContestMapper problemContestMapper,
                                                   VpAttemptMapper vpAttemptMapper,
                                                   ContestUserService contestUserService) {
        this.access = access;
        this.contestMapper = contestMapper;
        this.problemContestMapper = problemContestMapper;
        this.vpAttemptMapper = vpAttemptMapper;
        this.contestUserService = contestUserService;
    }

    /**
     * 正式赛程提交前的报名门槛（T10-1）。
     * <ul>
     *   <li>需要审核的比赛：必须已有 APPROVED 报名，PENDING/REJECTED/未报名都拒绝；</li>
     *   <li>不需要审核的比赛：未报名则自动报名（快照报名时 Rating），已有记录直接放行；</li>
     *   <li>资格超限不影响提交，只影响是否计入正式榜（eligible=0 打星号）。</li>
     * </ul>
     */
    private void ensureFormalParticipant(Contest contest, Long userId) {
        ContestUser registration = contestUserService.findRegistration(contest.getId(), userId);
        if (registration == null) {
            if (Integer.valueOf(1).equals(contest.getRegistrationReview())) {
                throw new BusinessException(ContestErrorCode.CONTEST_REGISTRATION_REQUIRED, "请先报名并通过审核后再提交");
            }
            contestUserService.registerContest(contest.getId(), userId);
            return;
        }
        if (ContestUserServiceImpl.STATUS_PENDING.equals(registration.getStatus())) {
            throw new BusinessException(ContestErrorCode.CONTEST_REGISTRATION_PENDING, "报名审核中，暂不能提交");
        }
        if (ContestUserServiceImpl.STATUS_REJECTED.equals(registration.getStatus())) {
            throw new BusinessException(ContestErrorCode.CONTEST_REGISTRATION_REJECTED, "报名未通过审核，不能提交");
        }
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
        Contest contest = contestMapper.selectByIdForUpdate(contestId);
        if (contest == null) {
            throw new BusinessException(ContestErrorCode.CONTEST_NOT_FOUND, "比赛不存在");
        }
        if (problemContestMapper.countByContestAndProblem(contestId, problemId) == 0) {
            throw new BusinessException(ContestErrorCode.CONTEST_PROBLEM_NOT_FOUND, "题目不属于该比赛");
        }

        if (!access.canReadContestProblem(contestId, problemId, userId))
            throw new com.musealgo.oj.common.exception.ForbiddenException("无权向该比赛题目提交");
        LocalDateTime now = LocalDateTime.now();
        // 闭区间判定：恰好在开赛或结束时刻的提交应当被接受，
        // 否则开赛瞬间的提交会被拒、结束瞬间的提交会被判为超时提交
        if (contest.getStartTime() != null && contest.getEndTime() != null
                && !now.isBefore(contest.getStartTime()) && !now.isAfter(contest.getEndTime())) {
            // 正式赛程内：报名审核开启时只有 APPROVED 报名可提交；未开启时首次提交自动报名（保存报名时 Rating）。
            ensureFormalParticipant(contest, userId);
            // vpAttemptId 留空，标记这份提交计入正式榜单
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
            throw new BusinessException(ContestErrorCode.CONTEST_ATTEMPT_NOT_ACTIVE, "当前不在比赛或虚拟赛作答时间内");
        }
        return new SubmissionContext(contestId, attempt.getId());
    }
}
