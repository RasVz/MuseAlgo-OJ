package com.musealgo.oj.analytics.controller;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.common.exception.CommonErrorCode;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.musealgo.oj.analytics.application.WeaknessAnalysisService;
import com.musealgo.oj.analytics.entity.vo.WeaknessResultVO;
import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.common.exception.ForbiddenException;
import com.musealgo.oj.common.utils.SecurityUtils;

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
            throw new BusinessException(CommonErrorCode.AUTH_REQUIRED, "请先登录");
        }
        return Result.success(weaknessAnalysisService.analyze(userId, days, withAi));
    }
}
