package top.everblue.oj.contest.controller;

import top.everblue.oj.contest.entity.ProblemContest;
import top.everblue.oj.contest.service.ProblemContestService;
import top.everblue.oj.common.api.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 竞赛题目关联管理控制器
 */
@Tag(name = "比赛题目管理", description = "比赛内题目的增删与配置")
@RestController
@RequestMapping("/api/problem-contest")
@RequiredArgsConstructor
public class ProblemContestController {

    private final ProblemContestService problemContestService;

    /**
     * 添加题目到竞赛
     */
    @Operation(summary = "添加题目到比赛")
    @PostMapping("/add")
    public Result<Long> addProblemToContest(@RequestBody ProblemContest problemContest) {
        Long id = problemContestService.addProblemToContest(problemContest);
        return Result.success(id);
    }

    /**
     * 批量添加题目到竞赛
     */
    @Operation(summary = "批量添加题目到比赛")
    @PostMapping("/batch-add")
    public Result<Boolean> batchAddProblems(@RequestParam Long contestId,
                                            @RequestBody List<Long> problemIds) {
        boolean success = problemContestService.batchAddProblems(contestId, problemIds);
        return Result.success(success);
    }

    /**
     * 从竞赛中移除题目（逻辑删除）
     */
    @Operation(summary = "从比赛中移除题目")
    @PostMapping("/remove/{id}")
    public Result<Boolean> removeFromContest(@PathVariable Long id) {
        boolean success = problemContestService.removeFromContest(id);
        return Result.success(success);
    }

    /**
     * 更新题目在竞赛中的配置（顺序、分值、可见性）
     */
    @Operation(summary = "更新比赛题目配置")
    @PostMapping("/update")
    public Result<Boolean> updateProblemConfig(@RequestBody ProblemContest problemContest) {
        boolean success = problemContestService.updateProblemConfig(problemContest);
        return Result.success(success);
    }

    /**
     * 切换题目可见性（赛前隐藏/显示）
     */
    @Operation(summary = "切换题目可见性")
    @PostMapping("/toggle-visible/{id}")
    public Result<Boolean> toggleVisible(@PathVariable Long id,
                                         @RequestParam Integer visible) {
        boolean success = problemContestService.toggleVisible(id, visible);
        return Result.success(success);
    }

    /**
     * 获取竞赛的所有题目（包含题目详情）
     */
    @Operation(summary = "获取比赛题目列表")
    @GetMapping("/contest/{contestId}")
    public Result<List<ProblemContest>> getContestProblems(
            @PathVariable Long contestId,
            @RequestParam(defaultValue = "1") Integer visible) {
        List<ProblemContest> list = problemContestService.getContestProblems(contestId, visible);
        return Result.success(list);
    }

    /**
     * 获取竞赛题目列表（管理后台用，包含隐藏的）
     */
    @Operation(summary = "获取比赛题目列表（管理后台）")
    @GetMapping("/contest/{contestId}/admin")
    public Result<List<ProblemContest>> getContestProblemsAdmin(@PathVariable Long contestId) {
        List<ProblemContest> list = problemContestService.getContestProblemsAdmin(contestId);
        return Result.success(list);
    }

    /**
     * 根据ID查询
     */
    @Operation(summary = "根据ID查询比赛题目")
    @GetMapping("/{id}")
    public Result<ProblemContest> getById(@PathVariable Long id) {
        ProblemContest pc = problemContestService.getById(id);
        return Result.success(pc);
    }

    /**
     * 检查题目是否已在竞赛中
     */
    @Operation(summary = "检查题目是否已在比赛中")
    @GetMapping("/check")
    public Result<Boolean> checkExist(@RequestParam Long contestId,
                                      @RequestParam Long problemId) {
        boolean exist = problemContestService.checkExist(contestId, problemId);
        return Result.success(exist);
    }
}
