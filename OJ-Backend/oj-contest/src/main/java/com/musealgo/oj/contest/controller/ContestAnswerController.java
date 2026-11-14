package com.musealgo.oj.contest.controller;

import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.contest.application.ContestAnswerApplicationService;
import com.musealgo.oj.contest.entity.vo.ContestAnswerPaperVO;
import com.musealgo.oj.contest.entity.vo.ContestAnswerProblemVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 比赛参赛卷：比赛详情页「参赛」后的整卷答题页数据。
 * <p>题目内容只从这里下发；未开始不返回题目，正式赛程内未报名/审核中/未通过一律拒绝，
 * 结束后只有已参赛用户可只读复盘。排行榜仍在比赛详情页。</p>
 */
@Tag(name = "比赛参赛卷", description = "比赛答题页上下文与白名单题面")
@RestController
@RequestMapping("/contest")
@RequiredArgsConstructor
public class ContestAnswerController {

    private final ContestAnswerApplicationService contestAnswerApplicationService;

    @Operation(summary = "比赛参赛卷上下文")
    @GetMapping("/{contestId}/answer/paper")
    public Result<ContestAnswerPaperVO> paper(@PathVariable Long contestId) {
        return Result.success(contestAnswerApplicationService.paper(contestId));
    }

    @Operation(summary = "比赛参赛卷白名单题面")
    @GetMapping("/{contestId}/answer/problem/{problemId}")
    public Result<ContestAnswerProblemVO> problem(@PathVariable Long contestId, @PathVariable Long problemId) {
        return Result.success(contestAnswerApplicationService.problem(contestId, problemId));
    }
}
