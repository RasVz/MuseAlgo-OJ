package com.musealgo.oj.teaching.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.musealgo.oj.api.teaching.ClassQuery;
import com.musealgo.oj.teaching.entity.Class;
import com.musealgo.oj.teaching.entity.ClassStudent;
import com.musealgo.oj.teaching.service.ClassService;
import com.musealgo.oj.teaching.service.ClassStudentService;

import java.util.List;
import java.util.Optional;

/**
 * 班级只读契约适配器 — oj-teaching 侧实现 {@link ClassQuery}。
 * <p>
 * 对外只暴露 {@link ClassInfo} 只读投射与学生 ID 集合，不泄露实体。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class ClassQueryAdapter implements ClassQuery {

    private final ClassService classService;
    private final ClassStudentService classStudentService;

    @Override
    public Optional<ClassInfo> findById(Long classId) {
        if (classId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(classService.getById(classId))
                .map(c -> new ClassInfo(c.getId(), c.getName(), c.getTeacherId()));
    }

    @Override
    public List<Long> findOwnedIds(Long teacherId) {
        if (teacherId == null) return List.of();
        return classService.list(new LambdaQueryWrapper<Class>().eq(Class::getTeacherId, teacherId))
                .stream().map(Class::getId).toList();
    }

    @Override
    public List<Long> findAccessibleIds(Long userId) {
        if (userId == null) return List.of();
        java.util.Set<Long> ids = new java.util.HashSet<>(findOwnedIds(userId));
        classStudentService.list(new LambdaQueryWrapper<ClassStudent>().eq(ClassStudent::getStudentId, userId))
                .forEach(member -> ids.add(member.getClassId()));
        return List.copyOf(ids);
    }

    @Override
    public List<Long> findStudentIds(Long classId) {
        if (classId == null) {
            return List.of();
        }
        return classStudentService.list(new LambdaQueryWrapper<ClassStudent>()
                        .eq(ClassStudent::getClassId, classId))
                .stream()
                .map(ClassStudent::getStudentId)
                .distinct()
                .toList();
    }

    @Override
    public Optional<String> findStudentRemark(Long classId, Long studentId) {
        if (classId == null || studentId == null) {
            return Optional.empty();
        }
        ClassStudent membership = classStudentService.getOne(new LambdaQueryWrapper<ClassStudent>()
                .eq(ClassStudent::getClassId, classId)
                .eq(ClassStudent::getStudentId, studentId)
                .last("LIMIT 1"));
        if (membership == null || membership.getRemark() == null || membership.getRemark().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(membership.getRemark());
    }

    @Override
    public long countStudents(Long classId) {
        if (classId == null) {
            return 0;
        }
        Long count = classStudentService.count(new LambdaQueryWrapper<ClassStudent>()
                .eq(ClassStudent::getClassId, classId));
        return count == null ? 0 : count;
    }
}
