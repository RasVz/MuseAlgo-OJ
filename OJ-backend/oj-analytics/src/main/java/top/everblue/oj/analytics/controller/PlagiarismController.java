package top.everblue.oj.analytics.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import top.everblue.oj.analytics.application.PlagiarismService;
import top.everblue.oj.analytics.dto.PlagiarismReportVO;
import top.everblue.oj.analytics.dto.RunPlagiarismRequest;
import top.everblue.oj.analytics.dto.UpdatePlagiarismStatusRequest;
import top.everblue.oj.api.submission.SubmissionCodeQuery.CodePairSource;
import top.everblue.oj.common.api.Result;

import java.util.List;

/**
 * 代码查重接口（教师/管理员）。
 * <p>
 * 权限在 application service 内经 {@code SecurityUtils.requireTeacherOrAdmin}
 * + 班级/比赛归属校验完成。
 * </p>
 */
@Tag(name = "代码查重", description = "班级内/比赛内同题提交两两比对与报告查询（M1 + M2）")
@RestController
@RequiredArgsConstructor
@RequestMapping("/teacher/plagiarism")
public class PlagiarismController {

    private final PlagiarismService plagiarismService;

    @Operation(summary = "触发查重", description = "同步执行班级内同题提交两两比对，返回本次新增报告数（重复执行幂等跳过）")
    @PostMapping("/run")
    public Result<Integer> run(@Valid @RequestBody RunPlagiarismRequest request) {
        return Result.success(plagiarismService.runPlagiarismCheck(
                request.getClassId(), request.getProblemId()));
    }

    @Operation(summary = "触发比赛查重（M2）", description = "同步执行比赛内全部题目跨班两两比对，管理员或比赛创建者可调用，返回新增报告数")
    @PostMapping("/contest/{contestId}/run")
    public Result<Integer> runContest(@PathVariable Long contestId) {
        return Result.success(plagiarismService.runContestPlagiarismCheck(contestId));
    }

    @Operation(summary = "查重报告分页", description = "按班级/比赛/题目/状态过滤，相似度降序")
    @GetMapping("/reports")
    public Result<IPage<PlagiarismReportVO>> reports(
            @Parameter(description = "班级 ID（可选）")
            @RequestParam(required = false) Long classId,
            @Parameter(description = "比赛 ID（可选，M2）")
            @RequestParam(required = false) Long contestId,
            @Parameter(description = "题目 ID（可选）")
            @RequestParam(required = false) Long problemId,
            @Parameter(description = "状态：PENDING / REVIEWED / IGNORED（可选）")
            @RequestParam(required = false) String status,
            @Parameter(description = "页码，从 1 开始")
            @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数")
            @RequestParam(defaultValue = "10") long size) {
        return Result.success(plagiarismService.pageReports(classId, contestId, problemId, status, page, size));
    }

    @Operation(summary = "取提交代码", description = "报告展开对比两段代码用（教师/管理员可看）")
    @GetMapping("/codes")
    public Result<List<CodePairSource>> codes(
            @Parameter(description = "提交 ID 列表，逗号分隔")
            @RequestParam List<Long> submissionIds) {
        return Result.success(plagiarismService.findCodes(submissionIds));
    }

    @Operation(summary = "更新报告处置状态", description = "标记报告为已核实/已忽略（教师/管理员，按班级/比赛归属校验，处置可逆）")
    @PostMapping("/reports/{id}/status")
    public Result<Void> updateStatus(
            @Parameter(description = "报告 ID")
            @PathVariable Long id,
            @Valid @RequestBody UpdatePlagiarismStatusRequest request) {
        plagiarismService.updateReportStatus(id, request.getStatus());
        return Result.success(null);
    }
}
