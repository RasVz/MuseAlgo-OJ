package com.musealgo.oj.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import com.musealgo.oj.common.annotation.AuditLog;
import com.musealgo.oj.common.annotation.RateLimit;
import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.judge.application.RejudgeApplicationService;
import com.musealgo.oj.judge.dto.RejudgeBatchScope;
import com.musealgo.oj.judge.dto.RejudgeOperationVO;
import com.musealgo.oj.judge.dto.RejudgePreviewVO;
import com.musealgo.oj.system.entity.User;

import java.time.LocalDateTime;

/**
 * 受控重判管理入口（T8-1 单提交 + T8-2a 范围化批量）。
 * <p>
 * 权限：ADMIN（方法级 @PreAuthorize）。历史冻结策略在应用服务内二次校验；
 * 批量先 preview（只读），再按同一范围执行，单批上限 200。
 * </p>
 */
@RestController
@RequestMapping("/admin/rejudge")
@RequiredArgsConstructor
public class AdminRejudgeController {

    private final RejudgeApplicationService rejudgeApplicationService;

    @Operation(summary = "单提交重判（管理员）")
    @PreAuthorize("hasRole('ADMIN')")
    @AuditLog(module = "judge", action = "rejudge_submission")
    @PostMapping("/submissions/{submissionId}")
    public Result<RejudgeOperationVO> rejudgeSubmission(
            @PathVariable Long submissionId,
            @Valid @RequestBody SingleRequest request) {
        Long operatorId = SecurityUtils.getUserId();
        return Result.success(rejudgeApplicationService.rejudgeSingleSubmission(
                submissionId, request.reason(), operatorId, currentOperatorName()));
    }

    @Operation(summary = "批量重判 dry-run 预览（管理员，只读）")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/batch/preview")
    public Result<RejudgePreviewVO> previewBatch(@RequestBody BatchRequest request) {
        return Result.success(rejudgeApplicationService.previewBatch(request.toScope()));
    }

    @Operation(summary = "批量重判执行（管理员，单批≤200）")
    @PreAuthorize("hasRole('ADMIN')")
    @AuditLog(module = "judge", action = "rejudge_batch")
    @RateLimit(key = "rejudgeBatch", time = 60, count = 2)
    @PostMapping("/batch")
    public Result<RejudgeOperationVO> rejudgeBatch(@RequestBody BatchRequest request) {
        Long operatorId = SecurityUtils.getUserId();
        return Result.success(rejudgeApplicationService.rejudgeBatch(
                request.toScope(), request.reason(), operatorId, currentOperatorName()));
    }

    @Operation(summary = "重判操作分页（管理员）")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/operations")
    public Result<Page<RejudgeOperationVO>> pageOperations(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        return Result.success(rejudgeApplicationService.pageOperations(
                page == null ? 1 : page, size == null ? 20 : size));
    }

    @Operation(summary = "重判操作详情（管理员）")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/operations/{id}")
    public Result<RejudgeOperationVO> operationDetail(@PathVariable Long id) {
        return Result.success(rejudgeApplicationService.getOperation(id));
    }

    private String currentOperatorName() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof User user) {
            if (user.getNickname() != null && !user.getNickname().isBlank()) {
                return user.getNickname();
            }
            return user.getUsername();
        }
        return null;
    }

    /** 单提交重判请求体（原因必填，便于审计追责）。 */
    public record SingleRequest(
            @NotBlank(message = "重判原因不能为空") @Size(max = 500, message = "重判原因不能超过 500 字") String reason) {
    }

    /** 批量范围请求体；reason 在 preview 可空，在 execute 由服务校验必填。 */
    public record BatchRequest(
            String scopeType,
            Long problemId,
            Long contestId,
            LocalDateTime from,
            LocalDateTime to,
            String reason) {
        RejudgeBatchScope toScope() {
            return new RejudgeBatchScope(scopeType, problemId, contestId, from, to);
        }
    }
}
