package com.musealgo.oj.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.dto.AdminClassSummary;
import com.musealgo.oj.service.AdminClassDirectoryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 班级管理（管理员）：查看所有教师创建的班级。 */
@Tag(name = "班级管理（管理员）", description = "管理员查看全部班级")
@RestController
@RequestMapping("/admin/classes")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminClassController {

    private final AdminClassDirectoryService directoryService;

    @Operation(summary = "全部班级列表")
    @GetMapping("/list")
    public Result<IPage<AdminClassSummary>> list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "all") String memberStatus,
            @RequestParam(defaultValue = "newest") String sort) {
        return Result.success(directoryService.list(page, size, keyword, memberStatus, sort));
    }

}
