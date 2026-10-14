package top.everblue.oj.teaching.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.annotation.AuditLog;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.api.problem.ProblemListCommand;
import top.everblue.oj.api.problem.ProblemListView;
import top.everblue.oj.api.problem.ProblemListQuery;
import top.everblue.oj.api.submission.PageResult;
import top.everblue.oj.teaching.entity.Class;
import top.everblue.oj.teaching.service.ClassService;

import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 班级题单管理接口。
 * <p>
 * 题单的读写都<strong>通过 oj-api 的 {@link ProblemListQuery} / {@link ProblemListCommand} 契约</strong>，
 * 而不是直接注入 oj-problem 的 Mapper：题单本体归 oj-problem 管，本模块只是「班级维度」的编排方，
 * 走接口才能让 oj-teaching 不反向依赖 oj-problem 的内部结构（题单一旦拆表或换实现，这里不用改）。
 * </p>
 * <p>
 * 改删接口的两道校验顺序不能颠倒，也都不能省：
 * </p>
 * <ol>
 *   <li><strong>归属校验</strong>：{@code classId} 必须等于题单实际所属班级。否则
 *       {@code DELETE /class/1/problem-list/999} 这种「借别的班级的路径删别人的题单」就能得手——
 *       路径里的 classId 是调用方给的，本身不可信。</li>
 *   <li><strong>身份校验</strong>：创建者本人或该班级的任课教师，两者之一即可。
 *       这是「教师协作」的权限模型：不是只有建的人能改。</li>
 * </ol>
 * <p>
 * 两个接口的校验顺序略有不同（更新先判权限再判归属，删除相反），行为等价但顺序不统一。
 * 改动时不必强行保持现状差异，只需保证<strong>两道校验都执行</strong>——
 * 一旦某处只做了其中一道，就会出现「路径合法但内容越权」或反之的漏洞。
 * </p>
 * <p>
 * 注意列表接口是公开路径（题单需要学生在未加入判定前预览），因此它<strong>不校验权限</strong>；
 * 真正的数据边界由「班级题单本身是否可见」在查询侧保证。改动此接口时请先确认这一点是否仍然成立。
 * </p>
 */
// ==================== 班级题单管理接口 ====================

@Tag(name = "班级题单", description = "班级专属题单管理")
@RestController
@RequiredArgsConstructor
@RequestMapping("/class")
public class ClassProblemListController {

    private final ProblemListQuery problemListQuery;

    private final ProblemListCommand problemListCommand;

    private final ClassService classService;

    /**
     * 获取班级题单列表 (支持分页)
     * @param classId 班级ID
     * @param page 当前页
     * @param size 每页数量
     */
    @Operation(summary = "获取班级题单列表")
    @GetMapping("/{classId}/problem-list")
    public Result<PageResult<ProblemListView>> getClassProblemLists(
            @PathVariable("classId") Long classId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.success(problemListQuery.findClassProblemListPage(classId, page, size));
    }

    /**
     * 为班级添加新题单
     * @param classId 班级ID
     * @param body 题单信息（title/description）
     */
    @Operation(summary = "为班级添加题单")
    @AuditLog(module = "class", action = "add_class_problem_list")
    @PostMapping("/{classId}/problem-list")
    public Result<Long> addClassProblemList(
            @PathVariable("classId") Long classId,
            @RequestBody Map<String, String> body) {

        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            return Result.error("未登录");
        }

        String title = body.get("title");
        if (title == null || title.trim().isEmpty()) {
            return Result.error("题单名称不能为空");
        }

        Long id = problemListCommand.createClassProblemList(
                new ProblemListCommand.NewClassProblemList(classId, userId, title.trim(), body.get("description")));
        return Result.success("添加成功", id);
    }

    /**
     * 更新班级题单信息
     * @param classId 班级ID
     * @param id 题单ID
     * @param body 题单信息（title/description）
     */
    @Operation(summary = "更新班级题单")
    @AuditLog(module = "class", action = "update_class_problem_list")
    @PutMapping("/{classId}/problem-list/{id}")
    public Result<String> updateClassProblemList(
            @PathVariable("classId") Long classId,
            @PathVariable("id") Long id,
            @RequestBody Map<String, String> body) {

        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            return Result.error("登录已过期");
        }

        // 查询原题单
        ProblemListView old = problemListQuery.findById(id).orElse(null);
        if (old == null) {
            return Result.error("题单不存在");
        }

        // 校验权限：只能修改自己创建的题单或者是班级老师
        if (!old.creatorId().equals(userId)) {
            // 检查是否是班级老师
            Class cls = classService.getById(classId);
            if (cls == null || !cls.getTeacherId().equals(userId)) {
                return Result.error("无权修改");
            }
        }

        // 校验题单是否属于该班级
        if (!classId.equals(old.classId())) {
            return Result.error("题单不属于该班级");
        }

        problemListCommand.updateClassProblemList(id, body.get("title"), body.get("description"));
        return Result.success("更新成功");
    }

    /**
     * 删除班级题单
     * @param classId 班级ID
     * @param id 题单ID
     */
    @Operation(summary = "删除班级题单")
    @AuditLog(module = "class", action = "delete_class_problem_list")
    @DeleteMapping("/{classId}/problem-list/{id}")
    public Result<String> deleteClassProblemList(
            @PathVariable("classId") Long classId,
            @PathVariable("id") Long id) {

        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            return Result.error("登录已过期");
        }

        // 查询原题单
        ProblemListView old = problemListQuery.findById(id).orElse(null);
        if (old == null) {
            return Result.error("题单不存在");
        }

        // 校验题单是否属于该班级
        if (!classId.equals(old.classId())) {
            return Result.error("题单不属于该班级");
        }

        // 校验权限：只能删除自己创建的题单或者是班级老师
        if (!old.creatorId().equals(userId)) {
            // 检查是否是班级老师
            Class cls = classService.getById(classId);
            if (cls == null || !cls.getTeacherId().equals(userId)) {
                return Result.error("无权删除");
            }
        }

        problemListCommand.removeById(id);
        return Result.success("删除成功");
    }

}
