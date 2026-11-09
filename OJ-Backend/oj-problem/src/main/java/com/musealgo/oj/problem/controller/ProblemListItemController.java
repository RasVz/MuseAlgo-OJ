package com.musealgo.oj.problem.controller;

import com.musealgo.oj.common.utils.RequestValues;

import com.musealgo.oj.common.exception.ErrorResponses;
import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.common.exception.CommonErrorCode;
import com.musealgo.oj.problem.error.ProblemErrorCode;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper; // 【新增】引入 UpdateWrapper
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.problem.entity.Problem;
import com.musealgo.oj.problem.entity.ProblemList; // 【新增】引入实体
import com.musealgo.oj.problem.entity.ProblemListItem;
import com.musealgo.oj.problem.entity.vo.ProblemVO;
import com.musealgo.oj.problem.service.ProblemListItemService;
import com.musealgo.oj.problem.service.ProblemListService; // 【新增】引入题单Service
import com.musealgo.oj.problem.service.ProblemService;

import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 题单内题目的增删与排序接口。
 * <p>
 * 只负责"某道题在某个题单里的位置"，题目本身的增删改在
 * {@link ProblemController}。计数（题单题目数）由这里在增删后同步更新，
 * 因此<b>不要</b>从题单详情接口侧改写该字段，否则两个入口会互相覆盖。
 * </p>
 */
@Tag(name = "题单题目管理", description = "题单内题目的增删排序")
@RestController
@RequiredArgsConstructor
@RequestMapping("/problem-list-item") // 保持你的路径不变
public class ProblemListItemController {

    private final ProblemListItemService problemListItemService;

    private final ProblemService problemService;

    // 用于在题目增删后同步题单主表的题目计数
    private final ProblemListService problemListService;
    private final com.musealgo.oj.problem.application.ProblemListAccessService access;
    private final com.musealgo.oj.problem.application.ProblemReadAccessService readAccess;

    /** 获取指定题单内的所有题目 */
    @Operation(summary = "获取题单内题目列表")
    @GetMapping("/list")
    public Result<List<ProblemVO>> getListProblems(
            @RequestParam Long listId
    ) {
        access.requireReadable(listId);
        Long userId = SecurityUtils.getUserId();
        List<ProblemVO> list = problemListItemService.selectProblemsWithStatus(listId, userId);
        return Result.success(list);
    }

    /** 题目详情页「加入题单」：返回当前用户创建的题单中已包含该题的 listId 集合 */
    @Operation(summary = "查询包含指定题目的题单 id 集合")
    @GetMapping("/contained")
    public Result<List<Long>> getContainedListIds(@RequestParam Long problemId) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return ErrorResponses.failure(CommonErrorCode.AUTH_REQUIRED, "未登录");
        return Result.success(problemListItemService.selectContainedListIds(problemId, userId));
    }

    /**
     * 向题单添加题目
     * 参数: { "problemListId": 1, "problemDisplayId": "1001" }
     */
    @Operation(summary = "向题单添加题目")
    @PostMapping("/add")
    @Transactional(rollbackFor = Exception.class)
    public Result<String> addProblem(@RequestBody Map<String, Object> params) {
        Object listIdObj = params.get("problemListId");
        Object displayIdObj = params.get("problemDisplayId");

        if (listIdObj == null || displayIdObj == null) {
            return ErrorResponses.failure(ProblemErrorCode.PROBLEM_INPUT_INVALID, "参数缺失");
        }

        Long listId;
        Long problemId;

        try {
            listId = Long.valueOf(listIdObj.toString());
            // 直接解析数字，不处理前缀
            problemId = Long.valueOf(displayIdObj.toString().trim());
        } catch (NumberFormatException e) {
            return ErrorResponses.failure(ProblemErrorCode.PROBLEM_INPUT_INVALID, "题目ID格式错误，请输入纯数字ID");
        }

        // 0. 越权防护：只能向自己创建的题单加题（管理员维护官方题单除外）
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return ErrorResponses.failure(CommonErrorCode.AUTH_REQUIRED, "未登录");
        if (!canOperateList(listId, userId)) {
            return ErrorResponses.failure(CommonErrorCode.ACCESS_DENIED, "无权操作该题单");
        }

        // 1. 查询题目是否存在
        Problem problem = problemService.getOne(new QueryWrapper<Problem>().eq("id", problemId));

        if (problem == null) {
            return ErrorResponses.failure(ProblemErrorCode.PROBLEM_NOT_FOUND, "题目不存在：" + problemId);
        }

        readAccess.requireReadable(problemId);

        // 2. 检查是否已经存在于该题单
        long count = problemListItemService.count(new QueryWrapper<ProblemListItem>()
                .eq("list_id", listId)
                .eq("problem_id", problem.getId()));

        if (count > 0) {
            return ErrorResponses.failure(ProblemErrorCode.PROBLEM_LIST_ITEM_ALREADY_EXISTS, "该题目已在题单中");
        }

        // 3. 添加关联
        ProblemListItem item = new ProblemListItem();
        item.setListId(listId);
        item.setProblemId(problem.getId());
        item.setSort(0);

        boolean saved = problemListItemService.save(item);
        if (!saved) throw new BusinessException(ProblemErrorCode.PROBLEM_LIST_WRITE_FAILED, "题单题目保存失败");

        // 【新增逻辑】如果保存成功，更新主表计数 +1
        if (saved) {
            UpdateWrapper<ProblemList> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", listId)
                    .setSql("problem_count = problem_count + 1");
            if (!problemListService.update(updateWrapper)) throw new BusinessException(ProblemErrorCode.PROBLEM_LIST_WRITE_FAILED, "题单题目计数更新失败");
        }

        return Result.success("添加成功");
    }

    /**
     * 从题单移除题目
     * 参数: { "problemListId": 1, "problemId": 1001 }
     */
    @Operation(summary = "从题单移除题目")
    @PostMapping("/remove")
    @Transactional(rollbackFor = Exception.class)
    public Result<String> removeProblem(@RequestBody Map<String, Object> params) {
        if (params.get("problemListId") == null || params.get("problemId") == null) {
            return ErrorResponses.failure(ProblemErrorCode.PROBLEM_INPUT_INVALID, "参数缺失");
        }

        Long listId;
        Long problemId;
        try {
            listId = RequestValues.longValue(params.get("problemListId"), "problemListId");
            problemId = RequestValues.longValue(params.get("problemId"), "problemId");
        } catch (NumberFormatException e) {
            return ErrorResponses.failure(ProblemErrorCode.PROBLEM_INPUT_INVALID, "参数格式错误");
        }

        // 越权防护：只能从自己创建的题单移除题目（管理员维护官方题单除外）
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return ErrorResponses.failure(CommonErrorCode.AUTH_REQUIRED, "未登录");
        if (!canOperateList(listId, userId)) {
            return ErrorResponses.failure(CommonErrorCode.ACCESS_DENIED, "无权操作该题单");
        }

        boolean removed = problemListItemService.remove(new QueryWrapper<ProblemListItem>()
                .eq("list_id", listId)
                .eq("problem_id", problemId));

        // 【新增逻辑】如果移除成功，更新主表计数 -1
        if (removed) {
            UpdateWrapper<ProblemList> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", listId)
                    .setSql("problem_count = problem_count - 1");
            if (!problemListService.update(updateWrapper)) throw new BusinessException(ProblemErrorCode.PROBLEM_LIST_WRITE_FAILED, "题单题目计数更新失败");

            return Result.success("移除成功");
        }

        throw new BusinessException(ProblemErrorCode.PROBLEM_LIST_ITEM_NOT_FOUND);
    }

    /**
     * 校验当前用户是否有权操作该题单：本人创建，或管理员维护官方题单(type=2)。
     */
    private boolean canOperateList(Long listId, Long userId) {
        ProblemList list = problemListService.getById(listId);
        if (list == null) return false;
        if (list.getCreatorId() != null && list.getCreatorId().equals(userId)) return true;
        return SecurityUtils.isAdmin() && list.getType() != null && list.getType() == 2;
    }
}
