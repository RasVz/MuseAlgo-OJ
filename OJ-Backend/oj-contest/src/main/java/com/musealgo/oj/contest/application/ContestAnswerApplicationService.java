package com.musealgo.oj.contest.application;

import com.musealgo.oj.api.problem.ContestProblemStatementQuery;
import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.common.exception.CommonErrorCode;
import com.musealgo.oj.common.exception.ForbiddenException;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.contest.entity.Contest;
import com.musealgo.oj.contest.entity.ContestUser;
import com.musealgo.oj.contest.entity.ProblemContest;
import com.musealgo.oj.contest.entity.VpAttempt;
import com.musealgo.oj.contest.entity.vo.ContestAnswerPaperVO;
import com.musealgo.oj.contest.entity.vo.ContestAnswerProblemVO;
import com.musealgo.oj.contest.entity.vo.ContestProblemVO;
import com.musealgo.oj.contest.entity.vo.VpAttemptVO;
import com.musealgo.oj.contest.error.ContestErrorCode;
import com.musealgo.oj.contest.mapper.ProblemContestMapper;
import com.musealgo.oj.contest.service.ContestService;
import com.musealgo.oj.contest.service.ContestUserService;
import com.musealgo.oj.contest.service.ProblemContestService;
import com.musealgo.oj.contest.service.VpAttemptService;
import com.musealgo.oj.contest.service.impl.ContestUserServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 比赛参赛卷读模型。
 *
 * <p>比赛详情页继续负责信息、题目列表与排行榜；本服务只服务「点参赛后进入的整卷答题页」。
 * 所有题面字段按白名单裁剪，不在返回结构中携带难度、标签、通过率、提示与标准答案。
 * 正式赛程内只允许已报名（无需审核时自动报名）且审核通过的用户进入；未开始不返回题目，
 * 结束后仅已参赛用户可只读复盘。VP 进行中按 VP 的结束时间放行并允许交卷。</p>
 */
@Service
@RequiredArgsConstructor
public class ContestAnswerApplicationService {

    private static final String PHASE_NOT_STARTED = "NOT_STARTED";
    private static final String PHASE_RUNNING = "RUNNING";
    private static final String PHASE_ENDED = "ENDED";

    private static final String RESULT_ACM = "VERDICT_TIME_MEMORY";
    private static final String RESULT_OI_HIDDEN = "SUBMITTED_ONLY";
    private static final String RESULT_FULL = "FULL";

    private final ContestService contestService;
    private final ContestUserService contestUserService;
    private final ProblemContestService problemContestService;
    private final ProblemContestMapper problemContestMapper;
    private final VpAttemptService vpAttemptService;
    private final ContestProblemStatementQuery statementQuery;

    public ContestAnswerPaperVO paper(Long contestId) {
        Long userId = requireUserId();
        Contest contest = requireContest(contestId);
        ContestAccessPolicy.requireReadable(contest);
        AnswerContext context = resolveContext(contest, userId);

        ContestAnswerPaperVO vo = new ContestAnswerPaperVO();
        vo.setContestId(contest.getId());
        vo.setTitle(contest.getTitle());
        vo.setMode(contest.getMode());
        vo.setStatus(context.phase());
        vo.setServerTime(LocalDateTime.now());
        vo.setStartTime(contest.getStartTime());
        vo.setEndTime(contest.getEndTime());
        vo.setDeadline(context.deadline());
        vo.setRemainingSeconds(context.remainingSeconds());
        vo.setCanSubmit(context.canSubmit());
        vo.setCanSelfTest(context.canSeeProblems());
        vo.setCanFinish(context.canFinish());
        vo.setResultVisibility(context.resultVisibility());
        vo.setRegistrationStatus(context.registrationStatus());
        vo.setRegistrationReviewRequired(Integer.valueOf(1).equals(contest.getRegistrationReview()));
        if (context.canSeeProblems()) {
            vo.setProblems(loadProblems(context, userId));
        }
        return vo;
    }

    public ContestAnswerProblemVO problem(Long contestId, Long problemId) {
        Long userId = requireUserId();
        Contest contest = requireContest(contestId);
        ContestAccessPolicy.requireReadable(contest);
        AnswerContext context = resolveContext(contest, userId);
        if (!context.canSeeProblems()) {
            throw new ForbiddenException("比赛尚未开始，不能查看题目");
        }
        ProblemContest relation = problemContestMapper.selectOne(new LambdaQueryWrapper<ProblemContest>()
                .eq(ProblemContest::getContestId, contestId)
                .eq(ProblemContest::getProblemId, problemId)
                .eq(ProblemContest::getVisible, 1)
                .eq(ProblemContest::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (relation == null) {
            throw new BusinessException(ContestErrorCode.CONTEST_PROBLEM_NOT_FOUND, "题目不属于该比赛");
        }
        ContestProblemStatementQuery.Statement statement = statementQuery.load(problemId);
        if (statement == null) {
            throw new BusinessException(ContestErrorCode.CONTEST_PROBLEM_NOT_FOUND, "题目不存在");
        }

        ContestAnswerProblemVO vo = new ContestAnswerProblemVO();
        vo.setContestId(contestId);
        vo.setProblemId(problemId);
        vo.setOrder(relation.getProblemOrder());
        vo.setTitle(statement.title());
        vo.setContent(statement.content());
        vo.setInputDesc(statement.inputDesc());
        vo.setOutputDesc(statement.outputDesc());
        vo.setSamples(statement.samples() == null ? List.of() : statement.samples().stream().map(sample -> {
            ContestAnswerProblemVO.SampleVO item = new ContestAnswerProblemVO.SampleVO();
            item.setInput(sample.input());
            item.setOutput(sample.output());
            return item;
        }).toList());
        vo.setTimeLimit(statement.timeLimit());
        vo.setMemoryLimit(statement.memoryLimit());
        vo.setScore(relation.getScore());
        vo.setMyStatus(myStatus(context, latestStatus(contestId, problemId, userId)));
        vo.setCanSubmit(context.canSubmit());
        return vo;
    }

    /** 当前用户在本场比赛的报名状态（比赛详情页决定「参赛」按钮形态）。 */
    public ContestUser myRegistration(Long contestId) {
        Long userId = requireUserId();
        Contest contest = requireContest(contestId);
        ContestAccessPolicy.requireReadable(contest);
        return contestUserService.findRegistration(contestId, userId);
    }

    private List<ContestAnswerPaperVO.ProblemItem> loadProblems(AnswerContext context, Long userId) {
        List<ContestProblemVO> rows = problemContestService.selectContestProblemList(context.contest().getId(), 1, userId);
        List<ContestAnswerPaperVO.ProblemItem> items = new ArrayList<>(rows.size());
        int order = 0;
        for (ContestProblemVO row : rows) {
            ContestAnswerPaperVO.ProblemItem item = new ContestAnswerPaperVO.ProblemItem();
            item.setProblemId(row.getProblemId());
            item.setOrder(++order);
            item.setTitle(row.getTitle());
            item.setScore(row.getScore());
            item.setMyStatus(myStatus(context, row.getStatus()));
            item.setPending("0".equals(row.getStatus()));
            items.add(item);
        }
        return items;
    }

    private String myStatus(AnswerContext context, String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return "UNTOUCHED";
        }
        // OI 赛中与 OI VP 进行中只确认「已提交」，不泄露判题结论。
        if (RESULT_OI_HIDDEN.equals(context.resultVisibility())) {
            return "SUBMITTED";
        }
        if ("1".equals(rawStatus)) {
            return "SOLVED";
        }
        if ("0".equals(rawStatus)) {
            return "PENDING";
        }
        return "ATTEMPTED";
    }

    private String latestStatus(Long contestId, Long problemId, Long userId) {
        List<ContestProblemVO> rows = problemContestService.selectContestProblemList(contestId, 1, userId);
        return rows.stream()
                .filter(row -> Objects.equals(problemId, row.getProblemId()))
                .map(ContestProblemVO::getStatus)
                .findFirst().orElse(null);
    }

    private AnswerContext resolveContext(Contest contest, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = contest.getStartTime();
        LocalDateTime end = contest.getEndTime();
        ContestUser registration = contestUserService.findRegistration(contest.getId(), userId);
        VpAttemptVO vp = vpAttemptService.getMy(contest.getId(), userId);
        boolean vpRunning = vp != null && VpAttempt.STATUS_RUNNING.equals(vp.getStatus())
                && vp.getEndTime() != null && vp.getEndTime().isAfter(now);

        if (start == null || now.isBefore(start)) {
            return new AnswerContext(contest, PHASE_NOT_STARTED, registration, false, false, false,
                    RESULT_FULL, start, secondsUntil(now, start));
        }

        if (end == null || now.isBefore(end) || now.isEqual(end)) {
            registration = ensureFormalParticipant(contest, userId, registration);
            boolean oi = "OI".equalsIgnoreCase(contest.getMode());
            return new AnswerContext(contest, PHASE_RUNNING, registration, true, false, true,
                    oi ? RESULT_OI_HIDDEN : RESULT_ACM, end, secondsUntil(now, end));
        }

        if (vpRunning) {
            boolean oi = "OI".equalsIgnoreCase(contest.getMode());
            return new AnswerContext(contest, PHASE_RUNNING, registration, true, true, true,
                    oi ? RESULT_OI_HIDDEN : RESULT_ACM, vp.getEndTime(),
                    vp.getRemainingSeconds() == null ? secondsUntil(now, vp.getEndTime()) : vp.getRemainingSeconds());
        }

        // 赛后只读：已报名用户或发起过 VP（含已结束 VP）的用户都可以复盘。
        if (registration != null) {
            requireParticipant(registration);
        } else if (vp == null) {
            throw new ForbiddenException("比赛已结束，未参赛不能查看题目");
        }
        return new AnswerContext(contest, PHASE_ENDED, registration, false, false, true,
                RESULT_FULL, end, 0L);
    }

    /**
     * 正式赛程内的准入：未报名且无需审核时自动报名，与提交入口的口径一致；
     * 需要审核时未报名、审核中、审核未通过都拒绝查看题面。
     */
    private ContestUser ensureFormalParticipant(Contest contest, Long userId, ContestUser registration) {
        if (registration == null) {
            if (Integer.valueOf(1).equals(contest.getRegistrationReview())) {
                throw new BusinessException(ContestErrorCode.CONTEST_REGISTRATION_REQUIRED, "请先报名并通过审核后查看题目");
            }
            try {
                contestUserService.registerContest(contest.getId(), userId);
            } catch (BusinessException conflict) {
                // 并发点击可能已被另一次请求报名成功；只要能查到记录就继续，否则原样抛出。
                registration = contestUserService.findRegistration(contest.getId(), userId);
                if (registration == null) {
                    throw conflict;
                }
            }
            registration = contestUserService.findRegistration(contest.getId(), userId);
        }
        requireParticipant(registration);
        return registration;
    }

    private void requireParticipant(ContestUser registration) {
        if (registration == null) {
            throw new ForbiddenException("比赛已结束，未参赛不能查看题目");
        }
        if (ContestUserServiceImpl.STATUS_PENDING.equals(registration.getStatus())) {
            throw new BusinessException(ContestErrorCode.CONTEST_REGISTRATION_PENDING, "报名审核中，暂不能查看题目");
        }
        if (ContestUserServiceImpl.STATUS_REJECTED.equals(registration.getStatus())) {
            throw new BusinessException(ContestErrorCode.CONTEST_REGISTRATION_REJECTED, "报名未通过审核，不能查看题目");
        }
    }

    private Contest requireContest(Long contestId) {
        Contest contest = contestService.getById(contestId);
        if (contest == null) {
            throw new BusinessException(ContestErrorCode.CONTEST_NOT_FOUND, "比赛不存在");
        }
        return contest;
    }

    private static long secondsUntil(LocalDateTime now, LocalDateTime target) {
        if (target == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(now, target).getSeconds());
    }

    private static Long requireUserId() {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            throw new BusinessException(CommonErrorCode.AUTH_REQUIRED, "请先登录");
        }
        return userId;
    }

    private record AnswerContext(
            Contest contest,
            String phase,
            ContestUser registration,
            boolean canSubmit,
            boolean canFinish,
            boolean canSeeProblems,
            String resultVisibility,
            LocalDateTime deadline,
            long remainingSeconds
    ) {
        String registrationStatus() {
            return registration == null ? null : registration.getStatus();
        }
    }
}
