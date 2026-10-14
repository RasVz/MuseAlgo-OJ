package top.everblue.oj.contest.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import top.everblue.oj.api.submission.ContestSubmission;
import top.everblue.oj.api.submission.ContestSubmissionPageRequest;
import top.everblue.oj.api.submission.ContestSubmissionQuery;
import top.everblue.oj.api.submission.PageResult;
import top.everblue.oj.api.submission.SubmissionSummary;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.annotation.AuditLog;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.contest.application.ContestManagementApplicationService;
import top.everblue.oj.contest.entity.Contest;
import top.everblue.oj.contest.entity.ContestUser;
import top.everblue.oj.api.user.UserDisplayInfo;
import top.everblue.oj.contest.entity.vo.ContestProblemVO;
import top.everblue.oj.api.problem.ProblemQuery;
import top.everblue.oj.api.user.UserQuery;
import top.everblue.oj.contest.service.ContestService;
import top.everblue.oj.contest.service.ContestUserService;
import top.everblue.oj.contest.service.ProblemContestService;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 比赛控制器：列表 / 详情 / 创建 / 更新 / 删除 / 题目 / 提交记录。
 * <p>
 * 排行榜相关端点已拆分至 {@link ContestRankController}
 * （2026-08-15，原 372 行 → 两文件），URL 与行为零变化。
 */
@Tag(name = "比赛管理", description = "比赛的创建、列表、详情、题目与提交记录")
@RestController
@RequestMapping("/contest")
@RequiredArgsConstructor
public class ContestController {

    private final ContestService contestService;

    private final ContestUserService contestUserService;

    private final ProblemContestService problemContestService;

    private final UserQuery userQuery;

    private final ProblemQuery problemQuery;

    private final ContestManagementApplicationService contestManagementApplicationService;

    private final ContestSubmissionQuery contestSubmissionQuery;

    // === 1. 获取比赛列表 (支持关键字搜索 + 状态自定义排序 + 可选 visible 筛选) ===
    @Operation(summary = "即将开始的比赛数量")
    @GetMapping("/upcoming-count")
    public Result<Integer> upcomingCount() {
        int count = contestService.lambdaQuery()
                .gt(Contest::getStartTime, new java.util.Date())
                .eq(Contest::getVisible, 1)
                .count().intValue();
        return Result.success(count);
    }

    @GetMapping("/list")
    public Result<Page<Contest>> getList(
            @RequestParam(defaultValue = "1") Integer currentPage,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer visible
    ) {
        Page<Contest> pageParam = new Page<>(currentPage, pageSize);
        LambdaQueryWrapper<Contest> wrapper = new LambdaQueryWrapper<>();

        if (visible != null) {
            wrapper.eq(Contest::getVisible, visible);
        }

        if (StringUtils.hasText(keyword)) {
            wrapper.like(Contest::getTitle, keyword);
        }

        String customOrderSql = "ORDER BY " +
                "CASE " +
                "  WHEN NOW() >= start_time AND NOW() <= end_time THEN 1 " +
                "  WHEN NOW() < start_time THEN 2 " +
                "  ELSE 3 " +
                "END ASC, " +
                "start_time ASC";

        // 用 last() 追加而非 orderByAsc()：排序表达式含 CASE WHEN，无法用方法链表达。
        // 这里是全 Controller 唯一一处字符串拼接 SQL 的地方，且拼接内容完全由常量拼成、
        // 不含任何请求参数，因此没有注入风险（对比 ContestServiceImpl 那边由前端传排序字段，必须走白名单）
        wrapper.last(customOrderSql);

        Page<Contest> resultPage = contestService.page(pageParam, wrapper);
        List<Contest> records = resultPage.getRecords();

        if (!records.isEmpty()) {
            Long userId = null;
            try {
                userId = SecurityUtils.getUserId();
            } catch (Exception e) {
                // 游客访问列表页时拿不到登录态，属正常情况：
                // 不报错，直接让 registered 保持实体默认值 false（未报名）
            }

            // 只有登录用户才需要回填报名状态；游客跳过本次查询，省掉一次数据库往返
            if (userId != null) {
                List<Long> contestIds = records.stream().map(Contest::getId).collect(Collectors.toList());

                List<ContestUser> userRecords = contestUserService.list(new LambdaQueryWrapper<ContestUser>()
                        .eq(ContestUser::getUserId, userId)
                        .in(ContestUser::getContestId, contestIds));

                Set<Long> registeredContestIds = userRecords.stream()
                        .map(ContestUser::getContestId)
                        .collect(Collectors.toSet());

                for (Contest contest : records) {
                    contest.setRegistered(registeredContestIds.contains(contest.getId()));
                }
            }
        }

        return Result.success(resultPage);
    }

    // === 2. 获取单个比赛详情 ===
    @Operation(summary = "比赛详情")
    @GetMapping("/{id}")
    public Result<Contest> getDetail(@PathVariable Long id) {
        Contest contest = contestService.getById(id);
        if (contest == null) {
            return Result.error("比赛不存在");
        }
        return Result.success(contest);
    }

    // === 3. 创建比赛 ===
    @Operation(summary = "创建比赛")
    @AuditLog(module = "contest", action = "create_contest")
    @PostMapping("/add")
    @PreAuthorize("hasAuthority('contest:manage')")
    public Result<Contest> add(@RequestBody Contest contest) {
        Long authorId = SecurityUtils.getUserId();
        if (authorId == null) {
            return Result.error("请先登录");
        }

        return Result.success("创建成功", contestManagementApplicationService.create(contest, authorId));
    }

    // === 4. 更新比赛 ===
    @Operation(summary = "更新比赛")
    @AuditLog(module = "contest", action = "update_contest")
    @PostMapping("/update")
    @PreAuthorize("hasAuthority('contest:manage')")
    public Result<String> update(@RequestBody Contest contest) {
        contestManagementApplicationService.update(contest);
        return Result.success("更新成功");
    }

    // === 5. 删除比赛 ===
    @Operation(summary = "删除比赛")
    @AuditLog(module = "contest", action = "delete_contest")
    @PostMapping("/delete/{id}")
    @PreAuthorize("hasAuthority('contest:manage')")
    public Result<String> delete(@PathVariable Long id) {
        contestManagementApplicationService.delete(id);
        return Result.success("删除成功");
    }

    /**
     * 获取指定比赛的题目列表
     */
    @Operation(summary = "获取比赛题目列表")
    @GetMapping("/{contestId}/problem/list")
    public Result<List<ContestProblemVO>> selectContestProblemList(
            @PathVariable("contestId") Long contestId,
            @RequestParam(value = "visible", defaultValue = "1") Integer visible) {

        Long userId = SecurityUtils.getUserId();
        List<ContestProblemVO> list = problemContestService.selectContestProblemList(contestId, visible, userId);
        return Result.success(list);
    }

    /**
     * 获取比赛测评记录（返回严格过滤的安全 SubmissionVO，补充了题目名和用户名）
     */
    @Operation(summary = "比赛提交记录")
    @GetMapping("/{contestId}/submission/list")
    public Result<Page<SubmissionSummary>> getContestSubmissionList(
            @PathVariable Long contestId,
            @RequestParam(defaultValue = "1") Integer currentPage,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(defaultValue = "true") Boolean mineOnly,
            @RequestParam(required = false) Long problemId,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) Integer status
    ) {
        Contest contest = contestService.getById(contestId);
        if (contest == null) {
            return Result.error("比赛不存在");
        }

        Long currentUserId = SecurityUtils.getUserId();
        // OI 比赛进行中：其他人可以看到「谁提交了这题」（提交行为本身不敏感），
        // 但看不到判题结论——否则等于实时告知全场谁做出来了，构成泄题。
        // 与 ContestRankingSseController 里的榜单闸门是同一条规则的另一个落点。
        boolean hideOiResults = isOiContestInProgress(contest) && !SecurityUtils.isTeacherOrAdmin();

        // 匿名用户请求「只看我的」必然无结果，直接拒绝比返回空列表更明确
        if (Boolean.TRUE.equals(mineOnly) && currentUserId == null) {
            return Result.error(401, "请先登录后查看自己的提交记录");
        }

        PageResult<ContestSubmission> page = contestSubmissionQuery.findPage(new ContestSubmissionPageRequest(
                contestId,
                currentPage,
                pageSize,
                Boolean.TRUE.equals(mineOnly),
                currentUserId,
                problemId,
                language,
                // 状态过滤也一并关闭：留着过滤条件会让「筛选后无结果」与「结果被隐藏」难以区分，
                // 并间接泄露「有人拿到了非 AC 结论」这一信息
                hideOiResults ? null : status
        ));

        Page<SubmissionSummary> voPage = new Page<>(page.page(), page.size(), page.total());
        List<ContestSubmission> records = page.records();

        if (records == null || records.isEmpty()) {
            return Result.success(voPage);
        }

        Set<Long> userIds = records.stream().map(ContestSubmission::userId).collect(Collectors.toSet());
        Set<Long> problemIds = records.stream().map(ContestSubmission::problemId).collect(Collectors.toSet());

        Map<Long, UserDisplayInfo> userMap = userIds.isEmpty() ? new HashMap<>() : userQuery.findDisplayInfoBatch(userIds);
        Map<Long, String> problemMap = problemIds.isEmpty() ? new HashMap<>() : problemQuery.findTitleBatch(problemIds);

        List<SubmissionSummary> voList = records.stream().map(record -> {
            SubmissionSummary vo = new SubmissionSummary();
            vo.setId(record.id());
            vo.setProblemId(record.problemId());
            vo.setUserId(record.userId());
            vo.setStatus(hideOiResults ? null : record.status());
            if (hideOiResults) {
                // 隐藏结论时统一给一个固定的中间状态，而不是让 status 留空：
                // 前端据此渲染「判题中」样式，同时避免 null 被误渲染成「未提交」
                vo.setJudgePhase("SUBMITTED");
            }
            vo.setLanguage(record.language());
            vo.setCreateTime(record.submittedAt());

            vo.setProblemTitle(problemMap.getOrDefault(record.problemId(), "未知题目"));

            UserDisplayInfo u = userMap.get(record.userId());
            if (u != null) {
                vo.setUsername(StringUtils.hasText(u.nickname()) ? u.nickname() : u.username());
                vo.setUserAvatar(u.avatar());
            } else {
                vo.setUsername("未知用户");
            }

            return vo;
        }).collect(Collectors.toList());

        voPage.setRecords(voList);

        return Result.success(voPage);
    }

    /**
     * 判断比赛是否处于「OI 模式且正在进行中」的时间窗内，取闭区间（含起止时刻）。
     * <p>
     * 与 {@link ContestRankingSseController} 中同名判断的口径必须一致，
     * 否则会出现「榜单被挡住但提交记录能看到结论」这类绕过。
     * </p>
     */
    private boolean isOiContestInProgress(Contest contest) {
        if (contest == null || !"OI".equalsIgnoreCase(contest.getMode())
                || contest.getStartTime() == null || contest.getEndTime() == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        return !now.isBefore(contest.getStartTime()) && !now.isAfter(contest.getEndTime());
    }

}
