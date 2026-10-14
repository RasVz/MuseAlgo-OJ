package top.everblue.oj.api.teaching;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 班级只读查询契约 — 跨模块读取 {@code class} / {@code class_student} 数据的唯一入口。
 * <p>
 * 实现由 oj-teaching 模块提供（数据归属方），oj-analytics 画像/学情模块消费，
 * 禁止直接注入 ClassService / ClassStudentService 或实体。
 * </p>
 */
public interface ClassQuery {

    /** 按 ID 查询班级基础信息（不存在返回空）。 */
    Optional<ClassInfo> findById(Long classId);

    /** 查询班级全部学生 ID（去重）。 */
    List<Long> findStudentIds(Long classId);

    /** 查询学生在班级内的备注名（无备注返回空）。 */
    Optional<String> findStudentRemark(Long classId, Long studentId);

    /** 班级成员数。 */
    long countStudents(Long classId);

    /** 班级基础信息（跨模块只读投射）。 */
    record ClassInfo(Long id, String name, Long teacherId) {
    }
}
