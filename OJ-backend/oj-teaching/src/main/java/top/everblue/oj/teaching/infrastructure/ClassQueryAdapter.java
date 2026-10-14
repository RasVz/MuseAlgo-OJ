package top.everblue.oj.teaching.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.teaching.ClassQuery;
import top.everblue.oj.teaching.entity.Class;
import top.everblue.oj.teaching.entity.ClassStudent;
import top.everblue.oj.teaching.service.ClassService;
import top.everblue.oj.teaching.service.ClassStudentService;

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
