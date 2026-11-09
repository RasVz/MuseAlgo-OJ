package com.musealgo.oj.problem.controller;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.common.exception.ErrorResponses;
import com.musealgo.oj.common.exception.CommonErrorCode;
import com.musealgo.oj.problem.error.ProblemErrorCode;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.common.annotation.AuditLog;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.problem.entity.ProblemList;
import com.musealgo.oj.problem.entity.dto.ProblemListOrderDTO;
import com.musealgo.oj.problem.entity.dto.ProblemListSortDTO;
import com.musealgo.oj.problem.entity.vo.ProblemListVO;
import com.musealgo.oj.problem.service.ProblemListService;
import com.musealgo.oj.api.user.UserQuery;

import java.time.LocalDateTime;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 题单/作业 前端控制器
 */
@Tag(name = "题单管理", description = "题单的创建、编辑与查询")
@RestController
@RequiredArgsConstructor
@RequestMapping("/problem-list") // 统一使用短横线命名
public class ProblemListController {
    private final com.musealgo.oj.problem.application.ProblemListAccessService access;
    private final com.musealgo.oj.api.teaching.ClassQuery classes;
    private final UserQuery userQuery;
    private final ProblemListService problemListService;

    @Operation(summary = "更新题单内题目排序")
    @AuditLog(module = "problem_list", action = "update_problem_order")
    @PostMapping("/update-order")
    public Result<String> updateProblemOrder(@RequestBody ProblemListOrderDTO orderDTO) {
        problemListService.updateProblemOrder(orderDTO);
        return Result.success("success");
    }

    /**
     * 1. 通用题单列表 (官方题单、我的题单、作业等)
     */
    @Operation(summary = "题单列表（分页检索）")
    @GetMapping("/list")
    public Result<IPage<ProblemListVO>> getList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Integer type,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) Long creatorId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer privacy
    ) {
        IPage<ProblemListVO> result = problemListService.selectVOList(
                new Page<>(page, size), type, classId, creatorId, keyword, privacy
        );
        return Result.success(result);
    }

    @Operation(summary = "题单统计（总数/公开/私密/累计收藏）")
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats(@RequestParam(required = false) Integer type) {
        return Result.success(problemListService.getStats(type));
    }

    @Operation(summary = "题单手动排序（全量有序 id 列表，重排 sort_order）")
    @AuditLog(module = "problem_list", action = "update_sort")
    @PostMapping("/update-sort")
    public Result<String> updateSort(@RequestBody ProblemListSortDTO sortDTO) {
        if (sortDTO.getType() == null || sortDTO.getIds() == null || sortDTO.getIds().isEmpty()) {
            return ErrorResponses.failure(ProblemErrorCode.PROBLEM_INPUT_INVALID, "参数缺失：type 与 ids 必填");
        }
        problemListService.updateSort(sortDTO.getType(), sortDTO.getIds());
        return Result.success("排序已保存");
    }

    @Operation(summary = "创建题单")
    @AuditLog(module = "problem_list", action = "create_problem_list")
    @PostMapping("/add")
    public Result<Long> add(@RequestBody ProblemList problemList) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return ErrorResponses.failure(CommonErrorCode.AUTH_REQUIRED, "未登录");

        // 基础非空校验
        if (problemList.getTitle() == null || problemList.getTitle().trim().isEmpty()) {
            return ErrorResponses.failure(ProblemErrorCode.PROBLEM_INPUT_INVALID, "标题不能为空");
        }

        // 官方题单(type=2)仅管理员可创建
        if (problemList.getType() != null && problemList.getType() == 2 && !SecurityUtils.isAdmin()) {
            return ErrorResponses.failure(CommonErrorCode.ACCESS_DENIED, "仅管理员可创建官方题单");
        }

        // 班级作业(4)/班级题单(3)必须带 classId：这两类的可见性与鉴权都按班级收敛
        if (problemList.getType() != null && (problemList.getType() == 3 || problemList.getType() == 4)) {
            if (problemList.getClassId() == null) {
                return ErrorResponses.failure(ProblemErrorCode.PROBLEM_INPUT_INVALID, "创建失败：班级作业/题单必须绑定班级ID (classId)");
            }
        } else if (problemList.getType() == null) {
            // 如果前端未传 type，默认视为个人私有题单 (type=1)
            problemList.setType((byte) 1);
        }

        problemList.setCreatorId(userId);
        problemList.setCreateTime(LocalDateTime.now());
        problemList.setUpdateTime(LocalDateTime.now());

        if (problemList.getType() == 3 || problemList.getType() == 4) {
            var classroom = classes.findById(problemList.getClassId()).orElse(null);
            if (classroom == null || !(SecurityUtils.isAdmin() || userId.equals(classroom.teacherId())))
                throw new com.musealgo.oj.common.exception.ForbiddenException("仅班级教师可创建班级题单");
        }
        // 默认初始化值防空指针
        if (problemList.getProblemCount() == null) problemList.setProblemCount(0);
        if (problemList.getFavoriteCount() == null) problemList.setFavoriteCount(0);
        if (problemList.getPrivacy() == null) problemList.setPrivacy(0);

        if (!problemListService.save(problemList)) throw new BusinessException(ProblemErrorCode.PROBLEM_LIST_WRITE_FAILED, "题单保存失败");

        // 返回新增的题单 ID 给前端，解决页面跳转到 /创建成功/edit 的 Bug
        return Result.success("创建成功", problemList.getId());
    }

    @Operation(summary = "更新题单")
    @AuditLog(module = "problem_list", action = "update_problem_list")
    @PostMapping("/update")
    public Result<String> update(@RequestBody ProblemList problemList) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return ErrorResponses.failure(CommonErrorCode.AUTH_REQUIRED, "登录已过期");

        // 校验权限：只能修改自己的题单
        ProblemList old = problemListService.getById(problemList.getId());
        if (old == null) return ErrorResponses.failure(ProblemErrorCode.PROBLEM_LIST_NOT_FOUND, "题单不存在");
        // 管理员可维护官方题单（含他人创建的）
        if (!old.getCreatorId().equals(userId)
                && !(SecurityUtils.isAdmin() && old.getType() != null && old.getType() == 2)) {
            return ErrorResponses.failure(CommonErrorCode.ACCESS_DENIED, "无权修改");
        }

        problemList.setUpdateTime(LocalDateTime.now());

        // 置空 creatorId/type/classId/createTime：updateById 跳过 null 字段，
        // 因此前端提交的这四项不会覆盖库中记录，题单归属与类型不可被篡改
        problemList.setCreatorId(null);
        problemList.setType(null);
        problemList.setClassId(null);
        problemList.setCreateTime(null);

        if (!problemListService.updateById(problemList)) throw new BusinessException(ProblemErrorCode.PROBLEM_LIST_WRITE_FAILED, "题单更新失败");
        return Result.success("更新成功");
    }

    @Operation(summary = "删除题单")
    @AuditLog(module = "problem_list", action = "delete_problem_list")
    @PostMapping("/delete/{id}")
    public Result<String> delete(@PathVariable Long id) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return ErrorResponses.failure(CommonErrorCode.AUTH_REQUIRED, "登录已过期");

        ProblemList old = problemListService.getById(id);
        if (old == null) return ErrorResponses.failure(ProblemErrorCode.PROBLEM_LIST_NOT_FOUND);
        if (!old.getCreatorId().equals(userId)) return ErrorResponses.failure(CommonErrorCode.ACCESS_DENIED, "无权删除该题单");
        if (!problemListService.removeById(id)) return ErrorResponses.failure(ProblemErrorCode.PROBLEM_LIST_NOT_FOUND);
        return Result.success("删除成功");
    }

    @Operation(summary = "题单详情")
    @GetMapping("/{id}")
    public Result<ProblemListVO> getDetail(@PathVariable Long id) {
        access.requireReadable(id);
        ProblemList problemList = problemListService.getById(id);
        if (problemList == null) return ErrorResponses.failure(ProblemErrorCode.PROBLEM_LIST_NOT_FOUND, "题单/作业不存在");

        ProblemListVO vo = new ProblemListVO();
        org.springframework.beans.BeanUtils.copyProperties(problemList, vo);

        userQuery.findDisplayInfo(problemList.getCreatorId()).ifPresent(creator -> {
            vo.setCreatorName(creator.nickname());
            vo.setCreatorAvatar(creator.avatar());
        });
        return Result.success(vo);
    }

    /**
     * 2. 学生作业汇总
     * 用于：首页 -> 我的作业
     */
    @Operation(summary = "我的作业列表")
    @GetMapping("/my-homeworks")
    public Result<IPage<ProblemListVO>> getMyHomeworks(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size
    ) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return ErrorResponses.failure(CommonErrorCode.AUTH_REQUIRED, "登录已过期");

        return Result.success(problemListService.getStudentHomeworks(page, size, userId));
    }

    @Operation(summary = "待完成数量")
    @GetMapping("/todo-count")
    public Result<Long> getTodoCount(@RequestParam Long userId) {
        if (!java.util.Objects.equals(userId, SecurityUtils.getUserId()) && !SecurityUtils.isAdmin())
            throw new com.musealgo.oj.common.exception.ForbiddenException("无权查看他人的作业进度");
        return Result.success(problemListService.getUnfinishedCount(userId));
    }
}
