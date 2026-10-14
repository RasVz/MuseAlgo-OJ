package top.everblue.oj.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.system.entity.User;
import top.everblue.oj.system.service.UserService;
import top.everblue.oj.teaching.entity.Class;
import top.everblue.oj.teaching.service.ClassService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 班级管理（管理员）：查看所有教师创建的班级。 */
@Tag(name = "班级管理（管理员）", description = "管理员查看全部班级")
@RestController
@RequestMapping("/admin/classes")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminClassController {

    private final ClassService classService;
    private final UserService userService;

    @Operation(summary = "全部班级列表")
    @GetMapping("/list")
    public Result<Page<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<Class> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            Long keywordId = parseId(keyword);
            Set<Long> matchedTeacherIds = userService.list(new LambdaQueryWrapper<User>()
                            .like(User::getUsername, keyword)
                            .or().like(User::getNickname, keyword))
                    .stream().map(User::getId).collect(Collectors.toSet());
            wrapper.and(w -> {
                w.like(Class::getName, keyword).or().like(Class::getSchool, keyword);
                if (keywordId != null) {
                    w.or().eq(Class::getId, keywordId);
                }
                if (!matchedTeacherIds.isEmpty()) {
                    w.or().in(Class::getTeacherId, matchedTeacherIds);
                }
            });
        }
        wrapper.orderByDesc(Class::getCreateTime);
        Page<Class> raw = classService.page(new Page<>(page, size), wrapper);

        Set<Long> teacherIds = raw.getRecords().stream()
                .map(Class::getTeacherId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> teachers = teacherIds.isEmpty()
                ? Map.of()
                : userService.listByIds(teacherIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u));

        Page<Map<String, Object>> result = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> rows = raw.getRecords().stream().map(c -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.getId());
            row.put("name", c.getName());
            row.put("description", c.getDescription());
            row.put("school", c.getSchool());
            row.put("teacherId", c.getTeacherId());
            User teacher = teachers.get(c.getTeacherId());
            row.put("teacherName", teacher == null ? null
                    : (teacher.getNickname() != null && !teacher.getNickname().isBlank()
                            ? teacher.getNickname() : teacher.getUsername()));
            row.put("studentCount", c.getStudentCount());
            row.put("createTime", c.getCreateTime());
            return row;
        }).toList();
        result.setRecords(rows);
        return Result.success(result);
    }

    private static Long parseId(String keyword) {
        try {
            return Long.parseLong(keyword);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
