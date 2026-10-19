package top.everblue.oj.judge.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import jakarta.validation.Valid;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import top.everblue.oj.common.annotation.RateLimit;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.api.community.FollowRelationQuery;
import top.everblue.oj.api.contest.ContestSchedule;
import top.everblue.oj.api.contest.ContestScheduleQuery;
import top.everblue.oj.api.submission.SubmissionSummary;
import top.everblue.oj.api.user.UserDisplayInfo;
import top.everblue.oj.api.user.UserQuery;
import top.everblue.oj.api.problem.ProblemQuery;
import top.everblue.oj.judge.entity.Submission;
import top.everblue.oj.judge.entity.SubmissionCode;
import top.everblue.oj.judge.entity.vo.SubmissionDetailVO;
import top.everblue.oj.judge.application.SubmissionApplicationService;
import top.everblue.oj.judge.application.SubmissionResultResponse;
import top.everblue.oj.judge.application.SubmitSolutionCommand;
import top.everblue.oj.judge.mapper.SubmissionCodeMapper;
import top.everblue.oj.judge.service.SubmissionService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 提交记录列表、提交判题、提交详情三个接口。
 * <p>
 * <strong>这个类的核心职责是可见性控制，而不是 CRUD。</strong> 判题数据在比赛期间是敏感信息：
 * 别人能否看到某次提交的结果、能否看到源码，取决于比赛模式、比赛是否进行中、以及提交人是否开启了分享。
 * 规则散落在三处（列表过滤、详情鉴权、OI 脱敏），改动任一处都要回头检查另外两处。
 * </p>
 * <p>
 * 三条不可省略的约束：
 * </p>
 * <ol>
 *   <li><strong>列表只查轻量列</strong>（{@code wrapper.select}）。提交量大且列表是最热的接口，
 *       带上 code / error_message 大字段会让每次翻页都搬运整段源码。</li>
 *   <li><strong>OI 赛中对所有人脱敏，包括提交者本人</strong>（{@code sanitizeForOi}）。
 *       让自己也看不到是为了防止「提交完看一眼对不对」这种行为泄漏题目分布。
 *       教师/管理员不受此限。</li>
 *   <li><strong>隐私过滤排除自己。</strong> 关闭了分享的用户，其提交不应出现在别人的列表里，
 *       但自己必须始终能看到自己的记录——过滤名单里要减掉当前登录用户。</li>
 * </ol>
 * <p>
 * 详情页源码从 {@code submission_code} 单独补载（V50 拆表），这是拆表的直接收益：
 * 列表查询不再连带读取大字段。改详情接口时不要试图从主表拿 code，那已经没有这列了。
 * </p>
 * <p>
 * 另外注意列表脱敏里的判空：{@code contestId} / {@code vpAttemptId} 可能是 null，
 * 而 {@code Set.of()} 创建的不可变集合不接受 null 元素，{@code contains(null)} 会抛 NPE，
 * 所以必须先判非空再 contains。
 * </p>
 */
@Tag(name = "判题提交", description = "代码提交与判题结果查询")
@RestController
@RequiredArgsConstructor
@RequestMapping("/submission")
public class SubmissionController {

    private final SubmissionService submissionService;
    private final UserQuery userQuery;
    private final ProblemQuery problemQuery;
    private final ContestScheduleQuery contestScheduleQuery;
    private final FollowRelationQuery followRelationQuery;
    private final SubmissionApplicationService submissionApplicationService;
    /** V50 拆表：详情页源码从 submission_code 补载 */
    private final SubmissionCodeMapper submissionCodeMapper;

    @Operation(summary = "提交记录列表")
    @GetMapping("/list")
    public Result<IPage<SubmissionSummary>> list(
            @RequestParam(value = "currentPage", defaultValue = "1") Integer currentPage,
            @RequestParam(value = "pageSize", defaultValue = "15") Integer pageSize,
            @RequestParam(required = false) Long problemId,
            @RequestParam(required = false) Byte status,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) Long currentUserId,
            @RequestParam(required = false) Boolean mineOnly,
            @RequestParam(required = false) Boolean followOnly
    ) {
        LambdaQueryWrapper<Submission> wrapper = new LambdaQueryWrapper<>();

        // B1（高并发方案）：列表只查轻量列，不拉 code/error_message 大字段（详情接口单独全量查询）
        wrapper.select(Submission::getId, Submission::getContestId, Submission::getVpAttemptId,
                        Submission::getProblemId, Submission::getUserId, Submission::getLanguage,
                        Submission::getStatus, Submission::getJudgePhase, Submission::getCreateTime)
                .eq(problemId != null, Submission::getProblemId, problemId)
                .eq(StringUtils.hasText(language), Submission::getLanguage, language);

        boolean privileged = SecurityUtils.isTeacherOrAdmin();
        Set<Long> hiddenContestIds = privileged ? Set.of() : contestScheduleQuery.findRunningOiContestIds();
        Set<Long> hiddenVpAttemptIds = privileged ? Set.of() : contestScheduleQuery.findRunningOiVpAttemptIds();
        if (status != null) {
            if (privileged) {
                wrapper.eq(Submission::getStatus, status);
            } else {
                if (hiddenContestIds.isEmpty() && hiddenVpAttemptIds.isEmpty()) {
                    wrapper.eq(Submission::getStatus, status);
                } else {
                    wrapper.and(w -> {
                        w.eq(Submission::getStatus, status);
                        if (!hiddenContestIds.isEmpty()) {
                            w.or().in(Submission::getContestId, hiddenContestIds);
                        }
                        if (!hiddenVpAttemptIds.isEmpty()) {
                            w.or().in(Submission::getVpAttemptId, hiddenVpAttemptIds);
                        }
                    });
                }
            }
        }

        Long loginUserId = SecurityUtils.getUserId();

        if (Boolean.TRUE.equals(mineOnly) && loginUserId != null) {
            wrapper.eq(Submission::getUserId, loginUserId);
        } else if (Boolean.TRUE.equals(followOnly) && loginUserId != null) {
            List<Long> targetUserIds = new ArrayList<>(followRelationQuery.findFollowedUserIds(loginUserId));
            targetUserIds.add(loginUserId);
            wrapper.in(Submission::getUserId, targetUserIds);
        }

        // 隐私：非"只看自己"且非管理员时，隐藏关闭了分享的用户的提交（本人自己的记录始终可见）
        if (!(Boolean.TRUE.equals(mineOnly) && loginUserId != null) && !SecurityUtils.isAdmin()) {
            List<Long> hiddenUserIds = userQuery.findUserIdsWithSubmissionsHidden().stream()
                    .filter(id -> !id.equals(loginUserId))
                    .toList();
            if (!hiddenUserIds.isEmpty()) {
                wrapper.notIn(Submission::getUserId, hiddenUserIds);
            }
        }

        wrapper.orderByDesc(Submission::getCreateTime);
        Page<Submission> pageParam = new Page<>(currentPage, pageSize);
        IPage<Submission> rawPage = submissionService.page(pageParam, wrapper);

        return Result.success(convertToVO(rawPage, hiddenContestIds, hiddenVpAttemptIds));
    }

    private IPage<SubmissionSummary> convertToVO(IPage<Submission> rawPage,
                                                  Set<Long> hiddenContestIds,
                                                  Set<Long> hiddenVpAttemptIds) {
        IPage<SubmissionSummary> voPage = new Page<>(rawPage.getCurrent(), rawPage.getSize(), rawPage.getTotal());
        List<Submission> records = rawPage.getRecords();

        if (records.isEmpty()) return voPage;

        Set<Long> userIds = records.stream().map(Submission::getUserId).collect(Collectors.toSet());
        Set<Long> problemIds = records.stream().map(Submission::getProblemId).collect(Collectors.toSet());

        Map<Long, UserDisplayInfo> userMap = userQuery.findDisplayInfoBatch(userIds);
        Map<Long, String> problemMap = problemQuery.findTitleBatch(problemIds);

        List<SubmissionSummary> voList = records.stream().map(record -> {
            SubmissionSummary vo = new SubmissionSummary();
            BeanUtils.copyProperties(record, vo);

            UserDisplayInfo info = userMap.get(record.getUserId());
            if (info != null) {
                vo.setUsername(StringUtils.hasText(info.nickname()) ? info.nickname() : info.username());
                vo.setUserAvatar(info.avatar());
            } else {
                vo.setUsername("未知用户");
            }
            vo.setProblemTitle(problemMap.getOrDefault(record.getProblemId(), "未知题目"));

            // OI 赛中（ADR-0003）：任何人（除教师/管理员）只见"已提交"，不泄漏结果
            // 普通提交的 contestId/vpAttemptId 可能为 null，Set.of() 的 contains(null) 会抛 NPE
            boolean hidden = (record.getContestId() != null && hiddenContestIds.contains(record.getContestId()))
                    || (record.getVpAttemptId() != null && hiddenVpAttemptIds.contains(record.getVpAttemptId()));
            if (hidden) {
                vo.setStatus(null);
                vo.setJudgePhase("SUBMITTED");
            }

            return vo;
        }).collect(Collectors.toList());

        voPage.setRecords(voList);
        return voPage;
    }

    /**
     * 提交代码进行判题。
     * <p>
     * 限流策略（@RateLimit）：
     * 同一用户每 10 秒内最多提交 1 次。超限时返回 429，提示"请 x 秒后再试"。
     * 限流基于 UserID（从 JWT Token 解析），未登录用户直接放行（后续业务层会拦截）。
     * </p>
     */
    @Operation(summary = "提交代码判题")
    @RateLimit(key = "submit", time = 10, count = 1)
    @PostMapping("/submit")
    public Result<SubmissionResultResponse> submit(@Valid @RequestBody SubmitSolutionCommand command) {
        return Result.success("判题完成", submissionApplicationService.submit(command));
    }

    @Operation(summary = "提交详情")
    @GetMapping("/detail")
    public Result<SubmissionDetailVO> detail(@RequestParam Long id) {
        Submission submission = submissionService.getById(id);
        if (submission == null) {
            return Result.error("评测记录不存在");
        }
        // V50 拆表：详情展示源码，从 submission_code 补载
        SubmissionCode codeRecord = submissionCodeMapper.selectById(id);
        submission.setCode(codeRecord == null ? null : codeRecord.getCode());

        Long currentUserId = SecurityUtils.getUserId();
        if (currentUserId == null) {
            return Result.error(401, "请先登录");
        }

        String problemTitle = problemQuery.findTitleById(submission.getProblemId()).orElse("未知题目");
        UserDisplayInfo author = userQuery.findDisplayInfo(submission.getUserId()).orElse(null);

        SubmissionDetailVO vo = new SubmissionDetailVO();
        BeanUtils.copyProperties(submission, vo);

        vo.setProblemTitle(problemTitle);
        if (author != null) {
            vo.setUsername(author.username());
            vo.setUserAvatar(author.avatar());
        }

        // 权限：本人和管理员始终可看；他人按"分享提交记录"开关控制（源码默认公开）
        boolean isOwner = currentUserId.equals(submission.getUserId());
        if (!isOwner && !SecurityUtils.isAdmin()) {
            // 正式比赛或 VP 进行中：仅本人可查看提交详情，避免泄题
            boolean activeVp = contestScheduleQuery.isVpAttemptRunning(submission.getVpAttemptId());
            if (submission.getContestId() != null && submission.getContestId() > 0) {
                ContestSchedule contest = contestScheduleQuery.findById(submission.getContestId()).orElse(null);
                boolean isContestEnded = contest != null && contest.endTime() != null
                        && LocalDateTime.now().isAfter(contest.endTime());
                if (!isContestEnded || activeVp) {
                    return Result.error(403, "比赛进行中，仅可查看自己的提交代码详情");
                }
            } else if (activeVp) {
                return Result.error(403, "虚拟赛进行中，仅可查看自己的提交代码详情");
            }
            // 非比赛（或比赛已结束）：按提交人是否分享判断
            if (!userQuery.isSubmissionSharingEnabled(submission.getUserId())) {
                return Result.error(403, "该用户未分享提交记录，无法查看");
            }
        }

        // OI 赛中（ADR-0003）：所有人（除教师/管理员）都看不到结果，包括本人
        if (submission.getContestId() != null && submission.getContestId() > 0
                && (isOiContestInProgress(submission.getContestId())
                    || (submission.getVpAttemptId() != null
                        && contestScheduleQuery.findRunningOiVpAttemptIds().contains(submission.getVpAttemptId())))
                && !SecurityUtils.isTeacherOrAdmin()) {
            return Result.success(sanitizeForOi(vo));
        }

        return Result.success(vo);
    }

    /** OI 赛中可见性：mode=OI 且当前时间在 [start, end] 内 */
    private boolean isOiContestInProgress(Long contestId) {
        ContestSchedule contest = contestScheduleQuery.findById(contestId).orElse(null);
        if (contest == null || !"OI".equalsIgnoreCase(contest.mode())
                || contest.startTime() == null || contest.endTime() == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        return !now.isBefore(contest.startTime()) && !now.isAfter(contest.endTime());
    }

    /** 仅返回"已提交"占位信息，剥离 status/score/耗时/错误详情 */
    private SubmissionDetailVO sanitizeForOi(SubmissionDetailVO vo) {
        vo.setStatus(null);
        vo.setJudgePhase("SUBMITTED");
        vo.setScore(null);
        vo.setRunTime(null);
        vo.setRunMemory(null);
        vo.setErrorMessage(null);
        return vo;
    }
}
