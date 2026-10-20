package top.everblue.oj.contest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.annotation.AuditLog;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.contest.entity.Contest;
import top.everblue.oj.contest.entity.ContestRank;
import top.everblue.oj.contest.entity.vo.ContestRankVO;
import top.everblue.oj.contest.service.ContestRankService;
import top.everblue.oj.contest.service.ContestService;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 比赛排行榜控制器：实时榜 / 最终榜 / 固化 / 重建 / CSV 导出。
 * <p>
 * 从 {@link ContestController} 拆分（2026-08-15，原 372 行 → 两文件），
 * URL 与行为零变化。
 */
@Tag(name = "比赛排行榜", description = "实时/最终排行榜、固化、重建与导出")
@RestController
@RequestMapping("/contest")
@RequiredArgsConstructor
public class ContestRankController {

    private final ContestService contestService;

    private final ContestRankService contestRankService;

    /**
     * 获取实时排行榜前 N 名
     */
    @Operation(summary = "获取实时排行榜")
    @GetMapping("/{contestId}/rank/list")
    public Result<List<ContestRankVO>> getRealtimeRankList(
            @PathVariable Long contestId,
            @RequestParam(defaultValue = "50") Integer limit
    ) {
        Contest contest = contestService.getById(contestId);
        if (isOiContestInProgress(contest) && !SecurityUtils.isTeacherOrAdmin()) {
            return Result.error(403, "OI 比赛进行中，暂不公布实时排名");
        }
        return Result.success(contestRankService.getRealtimeTopN(contestId, limit));
    }

    /**
     * 获取当前用户实时排名
     */
    @Operation(summary = "我的实时排名")
    @GetMapping("/{contestId}/rank/me")
    public Result<Long> getMyRealtimeRank(@PathVariable Long contestId) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            return Result.error(401, "请先登录");
        }
        Contest contest = contestService.getById(contestId);
        if (isOiContestInProgress(contest) && !SecurityUtils.isTeacherOrAdmin()) {
            return Result.error(403, "OI 比赛进行中，暂不公布实时排名");
        }
        Long rank = contestRankService.getRealtimeUserRank(contestId, userId);
        return Result.success(rank);
    }

    /**
     * 获取比赛最终排行榜（MySQL）
     */
    @Operation(summary = "获取最终排行榜")
    @GetMapping("/{contestId}/rank/final")
    public Result<List<ContestRank>> getFinalRankList(@PathVariable Long contestId) {
        Contest contest = contestService.getById(contestId);
        if (isOiContestInProgress(contest) && !SecurityUtils.isTeacherOrAdmin()) {
            return Result.error(403, "OI 比赛进行中，暂不公布排名");
        }
        return Result.success(contestRankService.getFinalRankList(contestId));
    }

    /**
     * 比赛结束后固化排行榜
     */
    @Operation(summary = "固化排行榜")
    @AuditLog(module = "contest", action = "finalize_rank")
    @PostMapping("/{contestId}/rank/finalize")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<String> finalizeContestRank(@PathVariable Long contestId) {
        contestRankService.finalizeContest(contestId);
        return Result.success("排行榜固化成功");
    }

    /**
     * 从 submission 校验重建实时排行榜
     */
    @Operation(summary = "重建实时排行榜")
    @AuditLog(module = "contest", action = "rebuild_rank")
    @PostMapping("/{contestId}/rank/rebuild")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<String> rebuildRealtimeRank(@PathVariable Long contestId) {
        contestRankService.rebuildRealtimeRankFromSubmission(contestId);
        return Result.success("实时排行榜重建成功");
    }

    @Operation(summary = "最终成绩 CSV 导出（管理员）")
    @GetMapping("/{contestId}/rank/export")
    @PreAuthorize("hasRole('ADMIN')")
    public void exportRank(@PathVariable Long contestId, HttpServletResponse response) throws IOException {
        String csv = contestRankService.exportCsv(contestId);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=contest-" + contestId + "-rank.csv");
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        OutputStream out = response.getOutputStream();
        out.write(bom);
        out.write(csv.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private boolean isOiContestInProgress(Contest contest) {
        if (contest == null || !"OI".equalsIgnoreCase(contest.getMode())
                || contest.getStartTime() == null || contest.getEndTime() == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        return !now.isBefore(contest.getStartTime()) && !now.isAfter(contest.getEndTime());
    }

}
