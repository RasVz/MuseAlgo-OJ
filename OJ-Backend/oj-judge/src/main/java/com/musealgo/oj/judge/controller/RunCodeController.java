package com.musealgo.oj.judge.controller;

import com.musealgo.oj.common.exception.ErrorResponses;
import com.musealgo.oj.judge.error.JudgeErrorCode;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import com.musealgo.oj.common.api.Result;

import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 普通练习试跑：不产生正式提交；返回短期回执以复用相同代码的运行事实。 */
@Tag(name = "在线运行", description = "代码在线运行调试")
@RestController
@RequiredArgsConstructor
@RequestMapping("/problem")
public class RunCodeController {

    private final com.musealgo.oj.api.judge.CodeExecutionGateway execution;
    private final com.musealgo.oj.api.problem.ProblemAccessQuery access;

    @Operation(summary = "在线运行代码")
    @PostMapping("/run")
    @com.musealgo.oj.common.annotation.RateLimit(key="codeRun", action="运行代码", time=60, count=30)
    public Result<Map<String, Object>> runCode(@RequestBody Map<String, String> params) {
        String code = params.get("code");
        String language = params.get("language");
        String input = params.get("input");

        if (code == null || code.trim().isEmpty()) {
            return ErrorResponses.failure(JudgeErrorCode.SUBMISSION_CODE_REQUIRED, "代码不能为空");
        }

        Long user = com.musealgo.oj.common.utils.SecurityUtils.getUserId();
        if (user == null) throw new com.musealgo.oj.common.exception.BusinessException(com.musealgo.oj.common.exception.CommonErrorCode.AUTH_REQUIRED);
        Long problem = params.get("problemId") == null ? null : com.musealgo.oj.common.utils.RequestValues.longValue(params.get("problemId"), "problemId");
        if (problem != null && problem <= 0) throw new com.musealgo.oj.common.exception.BusinessException(JudgeErrorCode.JUDGE_INPUT_INVALID);
        if (problem != null) access.requireReadable(problem);
        var run = execution.run(new com.musealgo.oj.api.judge.CodeExecutionGateway.Request(user, problem, code, language, input == null ? "" : input, "MANUAL"));
        var result = new java.util.LinkedHashMap<String,Object>();
        result.put("runId", run.runId()); result.put("status",run.status()); result.put("raw_stdout",run.stdout());
        result.put("stdout", run.status()==3 ? run.stdout() : !run.compileOutput().isBlank() ? run.compileOutput() : !run.stderr().isBlank() ? run.stderr() : run.message());
        result.put("stderr",run.stderr()); result.put("message",run.message()); result.put("compile_output",run.compileOutput()); result.put("time",run.time()); result.put("memory",run.memory()); result.put("truncated",run.truncated());
        return Result.success(result);

    }
}
