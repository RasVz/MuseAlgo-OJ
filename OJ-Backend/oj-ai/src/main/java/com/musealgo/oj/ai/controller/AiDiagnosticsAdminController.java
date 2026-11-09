package com.musealgo.oj.ai.controller;

import com.musealgo.oj.ai.diagnostics.*;
import com.musealgo.oj.ai.reference.TutorReferenceService;
import com.musealgo.oj.ai.error.AiErrorCode;
import com.musealgo.oj.api.system.SystemConfigCommand;
import com.musealgo.oj.api.problem.ProblemTutoringQuery;
import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/admin/ai/diagnostics")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AiDiagnosticsAdminController {
    private final DiagnosticStats stats;
    private final DiagnosisSettings settings;
    private final TutorReferenceService references;
    private final SystemConfigCommand config;
    private final ProblemTutoringQuery problems;
    @GetMapping public Result<Map<String,Object>> overview(@RequestParam(defaultValue="7") int days){return Result.success(stats.overview(days));}
    @GetMapping("/settings") public Result<Map<String,Integer>> settings(){return Result.success(Map.of("maxCases",settings.maxCases(),"waitSeconds",settings.waitSeconds()));}
    @com.musealgo.oj.common.annotation.AuditLog(module="ai",action="diagnostic_budget")
    @PutMapping("/settings") public Result<Void> settings(@RequestBody Map<String,Integer> body){
        Integer max=body.get("maxCases"),wait=body.get("waitSeconds");
        if(max==null||max<0||max>2||wait==null||wait<2||wait>30)throw new BusinessException(AiErrorCode.AI_INPUT_INVALID,"验证输入数0～2，等待上限2～30秒");
        config.set("ai.diagnostics.max-cases",max.toString());config.set("ai.diagnostics.wait-seconds",wait.toString());return Result.success(null);
    }
    @GetMapping("/reference") public Result<Map<String,Object>> reference(@RequestParam Long problemId){if(problemId==null||problemId<=0)throw new BusinessException(AiErrorCode.AI_INPUT_INVALID);problems.load(problemId);return Result.success(references.status(problemId));}
    @com.musealgo.oj.common.annotation.AuditLog(module="ai",action="tutor_reference")
    @PutMapping("/reference") public Result<Void> reference(@RequestBody Map<String,Long> body){
        Long problem=body.get("problemId");if(problem==null||problem<=0)throw new BusinessException(AiErrorCode.AI_INPUT_INVALID);
        problems.load(problem);references.select(problem,body.get("postId"));return Result.success(null);
    }
}
