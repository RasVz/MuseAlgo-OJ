package top.everblue.oj.teaching.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.annotation.AuditLog;
import top.everblue.oj.teaching.entity.ClassCourseware;
import top.everblue.oj.teaching.entity.Courseware;
import top.everblue.oj.teaching.service.ClassCoursewareService;
import top.everblue.oj.teaching.service.CoursewareService;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 班级课件关联 前端控制器
 * @since 2025-12-12
 */
@Tag(name = "班级课件", description = "课件添加、排序与可见性管理")
@RestController
@RequiredArgsConstructor
@RequestMapping("/class-courseware")
public class ClassCoursewareController {

    private final ClassCoursewareService classCoursewareService;

    private final CoursewareService coursewareService;
    private final top.everblue.oj.teaching.application.ClassMembershipApplicationService membership;

    private void requireRelationManager(Long id) {
        top.everblue.oj.common.utils.SecurityUtils.requireTeacherOrAdmin();
        ClassCourseware relation = classCoursewareService.getById(id);
        if (relation == null) throw new IllegalArgumentException("班级课件不存在");
        membership.requireClassManager(relation.getClassId());
    }

    /**
     * 获取某班级的课程目录
     * 特性：支持排序、适配前端 Tree 结构
     * 班级教师/管理员可取隐藏课件；学生仅可读取本班可见目录
     */
    @Operation(summary = "获取班级课件目录")
    @GetMapping("/list")
    public Result<List<Map<String, Object>>> getDirectory(@RequestParam Long classId,
                                                          @RequestParam(defaultValue = "1") Long isVisible) {

        boolean manager = membership.requireClassReader(classId);
        // 1. 查询关联关系 (按 sort 字段正序排列)
        LambdaQueryWrapper<ClassCourseware> relationWrapper = new LambdaQueryWrapper<>();
        relationWrapper.eq(ClassCourseware::getClassId, classId);

        if(!manager || Long.valueOf(1).equals(isVisible)){
            relationWrapper.eq(ClassCourseware::getIsVisible, true);
        }

        relationWrapper.orderByAsc(ClassCourseware::getSort);

        List<ClassCourseware> relations = classCoursewareService.list(relationWrapper);

        // 如果班级里没有课件，直接返回空列表
        if (relations == null || relations.isEmpty()) {
            return Result.success(new ArrayList<>());
        }

        // 2. 提取所有的 coursewareId
        List<Long> coursewareIds = relations.stream()
                .map(ClassCourseware::getCoursewareId)
                .collect(Collectors.toList());

        // 3. 批量查询课件详情
        LambdaQueryWrapper<Courseware> cwWrapper = new LambdaQueryWrapper<>();
        cwWrapper.in(Courseware::getId, coursewareIds);

        // 只查前端需要的字段，避免查出 content 大文本字段影响性能
        cwWrapper.select(Courseware::getId, Courseware::getTitle, Courseware::getUrl,
                Courseware::getProblemListId, Courseware::getDescription);

        List<Courseware> coursewares = coursewareService.list(cwWrapper);

        // 4. 将课件列表转为 Map<ID, Courseware>，方便后续通过 ID 快速取值
        Map<Long, Courseware> cwMap = coursewares.stream()
                .collect(Collectors.toMap(Courseware::getId, Function.identity()));

        // 5. 组装最终结果 (必须按照 relations 的顺序来组装)
        List<Map<String, Object>> resultList = new ArrayList<>();

        for (ClassCourseware relation : relations) {
            Courseware cw = cwMap.get(relation.getCoursewareId());
            // 防止关联表有数据，但课件表已经被物理删除了的情况
            if (cw != null) {
                Map<String, Object> node = new HashMap<>();
                node.put("id", cw.getId());
                node.put("title", cw.getTitle());
                node.put("type", "file");
                node.put("url", cw.getUrl());
                node.put("problemListId", cw.getProblemListId());
                node.put("description", cw.getDescription());

                // 关联表的信息
                node.put("relationId", relation.getId());
                node.put("isVisible", relation.getIsVisible());
                node.put("sort", relation.getSort());

                resultList.add(node);
            }
        }

        return Result.success(resultList);
    }

    /**
     * 添加课件到班级
     * 根据 url 查找课件，如果不存在则创建新课件
     */
    @Operation(summary = "添加课件到班级")
    @AuditLog(module = "class", action = "add_class_courseware")
    @PostMapping("/add")
    public Result<?> addCoursewareToClass(@RequestBody Map<String, Object> params) {
        try {
            classCoursewareService.addCoursewareToClass(
                    Long.valueOf(params.get("classId").toString()),
                    (String) params.get("title"),
                    (String) params.get("url"),
                    params.get("problemListId") != null ? Long.valueOf(params.get("problemListId").toString()) : null,
                    (String) params.get("description"));
            return Result.success("添加成功");
        } catch (IllegalStateException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 更新班级课件信息（可见性、排序等）
     */
    @Operation(summary = "更新班级课件信息")
    @AuditLog(module = "class", action = "update_class_courseware")
    @PostMapping("/update")
    public Result<?> updateClassCourseware(@RequestBody Map<String, Object> params) {
        requireRelationManager(Long.valueOf(params.get("id").toString()));
        Long id = Long.valueOf(params.get("id").toString());

        LambdaUpdateWrapper<ClassCourseware> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(ClassCourseware::getId, id);

        // 更新可见性
        if (params.containsKey("isVisible")) {
            Boolean isVisible = Boolean.valueOf(params.get("isVisible").toString());
            updateWrapper.set(ClassCourseware::getIsVisible, isVisible);
        }

        // 更新排序
        if (params.containsKey("sort")) {
            Integer sort = Integer.valueOf(params.get("sort").toString());
            updateWrapper.set(ClassCourseware::getSort, sort);
        }

        boolean success = classCoursewareService.update(updateWrapper);
        return success ? Result.success("更新成功") : Result.error("更新失败");
    }

    /**
     * 批量更新排序
     * 用于拖拽排序或上下移动后的批量更新
     */
    @Operation(summary = "批量更新排序")
    @AuditLog(module = "class", action = "update_courseware_sort")
    @PostMapping("/update-sort-batch")
    @Transactional
    public Result<?> updateSortBatch(@RequestBody List<Map<String, Object>> sortList) {
        for (Map<String, Object> item : sortList) requireRelationManager(Long.valueOf(item.get("id").toString()));
        for (Map<String, Object> item : sortList) {
            Long id = Long.valueOf(item.get("id").toString());
            Integer sort = Integer.valueOf(item.get("sort").toString());

            LambdaUpdateWrapper<ClassCourseware> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(ClassCourseware::getId, id)
                    .set(ClassCourseware::getSort, sort);
            classCoursewareService.update(updateWrapper);
        }
        return Result.success("排序更新成功");
    }

    /**
     * 从班级移除课件
     */
    @Operation(summary = "从班级移除课件")
    @AuditLog(module = "class", action = "remove_class_courseware")
    @PostMapping("/remove/{id}")
    public Result<?> removeCoursewareFromClass(@PathVariable Long id) {
        requireRelationManager(id);
        boolean success = classCoursewareService.removeById(id);
        return success ? Result.success("移除成功") : Result.error("移除失败");
    }
}
