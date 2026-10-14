package top.everblue.oj.problem.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.annotation.AuditLog;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.problem.entity.ProblemList;
import top.everblue.oj.problem.entity.dto.ProblemListOrderDTO;
import top.everblue.oj.problem.entity.dto.ProblemListSortDTO;
import top.everblue.oj.problem.entity.vo.ProblemListVO;
import top.everblue.oj.problem.service.ProblemListService;
import top.everblue.oj.api.user.UserQuery;

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
            return Result.error("参数缺失：type 与 ids 必填");
        }
        problemListService.updateSort(sortDTO.getType(), sortDTO.getIds());
        return Result.success("排序已保存");
    }

    @Operation(summary = "创建题单")
    @AuditLog(module = "problem_list", action = "create_problem_list")
    @PostMapping("/add")
    public Result<Long> add(@RequestBody ProblemList problemList) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return Result.error("未登录");

        // 基础非空校验
        if (problemList.getTitle() == null || problemList.getTitle().trim().isEmpty()) {
            return Result.error("标题不能为空");
        }

        // 官方题单(type=2)仅管理员可创建
        if (problemList.getType() != null && problemList.getType() == 2 && !SecurityUtils.isAdmin()) {
            return Result.error("仅管理员可创建官方题单");
        }

        // 班级作业(4)/班级题单(3)必须带 classId：这两类的可见性与鉴权都按班级收敛
        if (problemList.getType() != null && (problemList.getType() == 3 || problemList.getType() == 4)) {
            if (problemList.getClassId() == null) {
                return Result.error("创建失败：班级作业/题单必须绑定班级ID (classId)");
            }
        } else if (problemList.getType() == null) {
            // 如果前端未传 type，默认视为个人私有题单 (type=1)
            problemList.setType((byte) 1);
        }

        problemList.setCreatorId(userId);
        problemList.setCreateTime(LocalDateTime.now());
        problemList.setUpdateTime(LocalDateTime.now());

        // 默认初始化值防空指针
        if (problemList.getProblemCount() == null) problemList.setProblemCount(0);
        if (problemList.getFavoriteCount() == null) problemList.setFavoriteCount(0);
        if (problemList.getPrivacy() == null) problemList.setPrivacy(0);

        problemListService.save(problemList);

        // 返回新增的题单 ID 给前端，解决页面跳转到 /创建成功/edit 的 Bug
        return Result.success("创建成功", problemList.getId());
    }

    @Operation(summary = "更新题单")
    @AuditLog(module = "problem_list", action = "update_problem_list")
    @PostMapping("/update")
    public Result<String> update(@RequestBody ProblemList problemList) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return Result.error("登录已过期");

        // 校验权限：只能修改自己的题单
        ProblemList old = problemListService.getById(problemList.getId());
        if (old == null) return Result.error("题单不存在");
        // 管理员可维护官方题单（含他人创建的）
        if (!old.getCreatorId().equals(userId)
                && !(SecurityUtils.isAdmin() && old.getType() != null && old.getType() == 2)) {
            return Result.error("无权修改");
        }

        problemList.setUpdateTime(LocalDateTime.now());

        // 置空 creatorId/type/classId/createTime：updateById 跳过 null 字段，
        // 因此前端提交的这四项不会覆盖库中记录，题单归属与类型不可被篡改
        problemList.setCreatorId(null);
        problemList.setType(null);
        problemList.setClassId(null);
        problemList.setCreateTime(null);

        problemListService.updateById(problemList);
        return Result.success("更新成功");
    }

    @Operation(summary = "删除题单")
    @AuditLog(module = "problem_list", action = "delete_problem_list")
    @PostMapping("/delete/{id}")
    public Result<String> delete(@PathVariable Long id) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return Result.error("登录已过期");

        ProblemList old = problemListService.getById(id);
        if (old != null && old.getCreatorId().equals(userId)) {
            problemListService.removeById(id);
            return Result.success("删除成功");
        }
        return Result.error("删除失败");
    }

    @Operation(summary = "题单详情")
    @GetMapping("/{id}")
    public Result<ProblemListVO> getDetail(@PathVariable Long id) {
        ProblemList problemList = problemListService.getById(id);
        if (problemList == null) return Result.error("题单/作业不存在");

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
        if (userId == null) return Result.error("登录已过期");

        return Result.success(problemListService.getStudentHomeworks(page, size, userId));
    }

    @Operation(summary = "待完成数量")
    @GetMapping("/todo-count")
    public Result<Long> getTodoCount(@RequestParam Long userId) {
        return Result.success(problemListService.getUnfinishedCount(userId));
    }
}
