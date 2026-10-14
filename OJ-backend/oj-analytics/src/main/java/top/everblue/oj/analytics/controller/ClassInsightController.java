package top.everblue.oj.analytics.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.everblue.oj.analytics.application.ClassInsightService;
import top.everblue.oj.common.api.Result;

import java.util.Map;

/**
 * 班级学情：比赛管理右侧面板的聚合统计与 AI 解读。
 * <p>
 * GET 返回结构化统计（前端渲染图表），POST /ai 基于同一份统计生成 AI 解读。
 * 权限（班主任本人或管理员）在 {@link ClassInsightService} 内校验。
 * </p>
 */
@Tag(name = "班级学情", description = "班级近一周/一月学情聚合与 AI 解读")
@RestController
@RequestMapping("/class")
@RequiredArgsConstructor
public class ClassInsightController {

    private final ClassInsightService classInsightService;

    @Operation(summary = "班级学情聚合统计（近 N 天）")
    @GetMapping("/{classId}/insight")
    public Result<Map<String, Object>> insight(
            @Parameter(description = "班级 ID", required = true) @PathVariable Long classId,
            @Parameter(description = "统计天数，默认 30（近一周传 7）") @RequestParam(defaultValue = "30") Integer days) {
        return Result.success(classInsightService.aggregate(classId, days));
    }

    @Operation(summary = "班级学情 AI 解读（基于聚合数据）")
    @PostMapping("/{classId}/insight/ai")
    public Result<String> aiInsight(
            @Parameter(description = "班级 ID", required = true) @PathVariable Long classId,
            @Parameter(description = "统计天数，默认 30（近一周传 7）") @RequestParam(defaultValue = "30") Integer days) {
        return Result.success(classInsightService.generateAiInsight(classId, days));
    }
}
