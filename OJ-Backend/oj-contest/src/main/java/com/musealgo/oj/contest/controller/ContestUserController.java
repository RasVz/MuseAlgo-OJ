package com.musealgo.oj.contest.controller;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.common.exception.ErrorResponses;
import com.musealgo.oj.contest.error.ContestErrorCode;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.common.exception.ForbiddenException;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.contest.entity.Contest;
import com.musealgo.oj.contest.entity.ContestUser;
import com.musealgo.oj.contest.entity.vo.ContestRegistrationVO;
import com.musealgo.oj.contest.application.ContestAnswerApplicationService;
import com.musealgo.oj.contest.service.ContestService;
import com.musealgo.oj.contest.service.ContestUserService;
import com.musealgo.oj.contest.service.impl.ContestUserServiceImpl;

/**
 * 比赛报名端点（T10-1 扩展审核）。
 * <p>用户报名；比赛作者/管理员查看报名与审核。审核开启后只有 APPROVED 报名可以提交正式赛程内的提交。</p>
 */
@RestController
@RequestMapping("/contest")
@RequiredArgsConstructor
public class ContestUserController {

    private final ContestUserService contestUserService;
    private final ContestService contestService;
    private final ContestAnswerApplicationService contestAnswerApplicationService;

    @Operation(summary = "我的报名状态")
    @GetMapping("/{contestId}/registration/mine")
    public Result<MineRegistration> myRegistration(@PathVariable Long contestId) {
        ContestUser record = contestAnswerApplicationService.myRegistration(contestId);
        Contest contest = contestService.getById(contestId);
        return Result.success(new MineRegistration(
                record != null,
                record == null ? null : record.getStatus(),
                contest != null && Integer.valueOf(1).equals(contest.getRegistrationReview())));
    }

    @Operation(summary = "报名比赛")
    @PostMapping("/register/{contestId}")
    public Result<String> register(@PathVariable Long contestId) {
        Long userId = SecurityUtils.getUserId();
        try {
            ContestUser record = contestUserService.registerContest(contestId, userId);
            String message = ContestUserServiceImpl.STATUS_PENDING.equals(record.getStatus())
                    ? "报名已提交，等待审核" : "报名成功！";
            return Result.success(message);
        } catch (ForbiddenException denied) {
            return ErrorResponses.failure(denied.getErrorCode(), denied.getMessage());
        }

    }

    @Operation(summary = "报名列表（比赛作者/管理员）")
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    @GetMapping("/{contestId}/registrations")
    public Result<Page<ContestRegistrationVO>> registrations(
            @PathVariable Long contestId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        requireManage(contestId);
        return Result.success(contestUserService.pageRegistrations(
                contestId, page == null ? 1 : page, size == null ? 20 : size));
    }

    @Operation(summary = "审核报名（比赛作者/管理员）")
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    @PostMapping("/{contestId}/registrations/{userId}/review")
    public Result<String> review(@PathVariable Long contestId, @PathVariable Long userId,
                                 @RequestBody ReviewRequest request) {
        requireManage(contestId);
        Long reviewerId = SecurityUtils.getUserId();
        contestUserService.reviewRegistration(contestId, userId, request.approve(), request.reason(), reviewerId);
        return Result.success(request.approve() ? "已通过报名" : "已拒绝报名");
    }

    private void requireManage(Long contestId) {
        Contest contest = contestService.getById(contestId);
        if (contest == null) {
            throw new BusinessException(ContestErrorCode.CONTEST_NOT_FOUND, "比赛不存在");
        }
        Long currentUserId = SecurityUtils.getUserId();
        if (!SecurityUtils.isAdmin() && (currentUserId == null || !currentUserId.equals(contest.getAuthorId()))) {
            throw new com.musealgo.oj.common.exception.ForbiddenException("无权管理该比赛报名");
        }
    }

    /** 审核请求体。 */
    public record ReviewRequest(boolean approve, String reason) {
    }

    /** 比赛详情页「参赛」按钮所需的本人报名状态。 */
    public record MineRegistration(boolean registered, String status, boolean registrationReviewRequired) {
    }
}
