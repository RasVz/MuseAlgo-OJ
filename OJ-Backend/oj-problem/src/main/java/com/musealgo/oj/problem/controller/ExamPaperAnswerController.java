package com.musealgo.oj.problem.controller;

import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.problem.application.ExamPaperAnswerApplicationService;
import com.musealgo.oj.problem.entity.vo.ExamPaperAnswerProblemVO;
import com.musealgo.oj.problem.entity.vo.ExamPaperAnswerVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 成套试卷整卷答题页接口。
 *
 * <p>与试卷管理/成绩接口分开：这里只下发作答期允许看到的字段，访问校验统一在
 * {@link ExamPaperAnswerApplicationService} 内完成。</p>
 */
@Tag(name = "成套试卷答题", description = "整卷答题页的上下文与白名单题面")
@RestController
@RequiredArgsConstructor
@RequestMapping("/exam-paper")
public class ExamPaperAnswerController {

    private final ExamPaperAnswerApplicationService answerService;

    @Operation(summary = "整卷答题上下文")
    @GetMapping("/{paperId}/attempt/{attemptId}/answer/paper")
    public Result<ExamPaperAnswerVO> paper(
            @PathVariable Long paperId,
            @PathVariable Long attemptId) {
        return Result.success(answerService.paper(paperId, attemptId));
    }

    @Operation(summary = "整卷答题编程题题面")
    @GetMapping("/{paperId}/attempt/{attemptId}/answer/problem/{problemId}")
    public Result<ExamPaperAnswerProblemVO> problem(
            @PathVariable Long paperId,
            @PathVariable Long attemptId,
            @PathVariable Long problemId) {
        return Result.success(answerService.problem(paperId, attemptId, problemId));
    }
}
