package com.musealgo.oj.contest.controller;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.musealgo.oj.common.annotation.AuditLog;
import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.contest.entity.ContestLevel;
import com.musealgo.oj.contest.service.ContestLevelService;

import com.musealgo.oj.contest.application.ContestLevelManagementApplicationService;
import java.util.List;

/**
 * 比赛等级配置（T10-1，管理员）。等级 → Rating 范围的映射只在此维护；
 * 比赛创建/更新时把结果快照到 contest，之后改配置不追溯历史。
 */
@RestController
@RequestMapping("/admin/contest-levels")
@RequiredArgsConstructor
public class AdminContestLevelController {

    private final ContestLevelService levelService;
    private final ContestLevelManagementApplicationService application;

    @Operation(summary = "等级列表（管理员）")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public Result<List<ContestLevel>> list() {
        return Result.success(levelService.listAll());
    }

    @Operation(summary = "新增等级（管理员）")
    @PreAuthorize("hasRole('ADMIN')")
    @AuditLog(module = "contest", action = "create_contest_level")
    @PostMapping
    public Result<ContestLevel> create(@RequestBody ContestLevel level) {
        return Result.success(application.create(level));
    }

    @Operation(summary = "更新等级（管理员）")
    @PreAuthorize("hasRole('ADMIN')")
    @AuditLog(module = "contest", action = "update_contest_level")
    @PutMapping("/{id}")
    public Result<ContestLevel> update(@PathVariable Long id, @RequestBody ContestLevel level) {
        return Result.success(application.update(id, level));
    }
}
