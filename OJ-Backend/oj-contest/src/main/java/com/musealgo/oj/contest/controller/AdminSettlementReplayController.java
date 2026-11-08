package com.musealgo.oj.contest.controller;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.musealgo.oj.common.annotation.AuditLog;
import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.contest.entity.vo.SettlementReplayPreviewVO;
import com.musealgo.oj.contest.entity.vo.SettlementReplayResultVO;
import com.musealgo.oj.contest.service.ContestSettlementReplayService;

/**
 * T8-3 C：比赛结算链式重放。
 * <p>仅管理员；先预览，再用确认串执行。执行会重写起点及其后所有已固化比赛的
 * contest_rank/user_rating 并重算 user.rating，风险高，必须显式确认。</p>
 */
@RestController
@RequestMapping("/admin/settlement-replay")
@RequiredArgsConstructor
public class AdminSettlementReplayController {

    private final ContestSettlementReplayService replayService;

    @Operation(summary = "链式重放预览（管理员）")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{contestId}/preview")
    public Result<SettlementReplayPreviewVO> preview(@PathVariable Long contestId) {
        return Result.success(replayService.preview(contestId));
    }

    @Operation(summary = "执行链式重放（管理员，显式确认）")
    @PreAuthorize("hasRole('ADMIN')")
    @AuditLog(module = "contest", action = "replay_settlement_chain")
    @PostMapping("/{contestId}")
    public Result<SettlementReplayResultVO> execute(@PathVariable Long contestId,
                                                    @RequestBody ReplayRequest request) {
        return Result.success(replayService.execute(
                contestId, request.reason(), SecurityUtils.getUserId(), request.confirm()));
    }

    @GetMapping("/history")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<java.util.List<com.musealgo.oj.contest.entity.ContestSettlementReplay>> history() {
        return Result.success(replayService.history());
    }

    @GetMapping("/history/{replayId}")
    @PreAuthorize("hasRole('ADMIN')")
    @AuditLog(module = "contest", action = "read_replay_backup")
    public Result<com.musealgo.oj.contest.entity.ContestSettlementReplay> backup(@PathVariable Long replayId) {
        return Result.success(replayService.getReplay(replayId));
    }

    @GetMapping("/history/{replayId}/restore-preview")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<SettlementReplayPreviewVO> restorePreview(@PathVariable Long replayId) {
        return Result.success(replayService.previewRestore(replayId));
    }

    @PostMapping("/history/{replayId}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    @AuditLog(module = "contest", action = "restore_settlement_replay")
    public Result<SettlementReplayResultVO> restore(@PathVariable Long replayId, @RequestBody ReplayRequest request) {
        return Result.success(replayService.restore(replayId, request.reason(), SecurityUtils.getUserId(), request.confirm()));
    }

    public record ReplayRequest(String reason, String confirm) {
    }
}
