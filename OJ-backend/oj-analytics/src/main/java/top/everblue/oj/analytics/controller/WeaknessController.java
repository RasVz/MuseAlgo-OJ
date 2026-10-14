package top.everblue.oj.analytics.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.everblue.oj.analytics.application.WeaknessAnalysisService;
import top.everblue.oj.analytics.entity.vo.WeaknessResultVO;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.exception.ForbiddenException;
import top.everblue.oj.common.utils.SecurityUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 错题归因与训练计划（A3）。 */
@Tag(name = "错题归因", description = "薄弱知识点分析 + 个性化训练建议")
@RestController
@RequestMapping("/profile")
@RequiredArgsConstructor
public class WeaknessController {

    private final WeaknessAnalysisService weaknessAnalysisService;

    @Operation(summary = "错题归因与训练计划")
    @GetMapping("/weakness")
    public Result<WeaknessResultVO> weakness(
            @RequestParam(defaultValue = "30") Integer days,
            @RequestParam(defaultValue = "true") Boolean withAi) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            throw new ForbiddenException("请先登录");
        }
        return Result.success(weaknessAnalysisService.analyze(userId, days, withAi));
    }
}
