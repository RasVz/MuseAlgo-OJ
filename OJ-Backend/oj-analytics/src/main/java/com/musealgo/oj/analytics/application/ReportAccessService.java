package com.musealgo.oj.analytics.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.musealgo.oj.api.report.ReportActorSnapshot;
import com.musealgo.oj.api.teaching.ClassQuery;
import com.musealgo.oj.common.exception.ForbiddenException;
import com.musealgo.oj.common.utils.SecurityUtils;

/** T9-1 报告权限：班级归属 + 学生归属校验。 */
@Service
@RequiredArgsConstructor
public class ReportAccessService {

    private final ClassQuery classQuery;

    /** 当前教师必须是该班班主任，管理员放行。 */
    public void requireClassTeacher(Long classId) {
        SecurityUtils.requireTeacherOrAdmin();
        ClassQuery.ClassInfo info = classQuery.findById(classId)
                .orElseThrow(() -> new ForbiddenException("班级不存在"));
        Long currentUserId = SecurityUtils.getUserId();
        if (currentUserId == null
                || (!currentUserId.equals(info.teacherId()) && !SecurityUtils.isAdmin())) {
            throw new ForbiddenException("无权限操作该班级报告");
        }
    }

    /** 学生必须属于该班，避免用任意 studentId 越过班级范围。 */
    public void requireStudentInClass(Long classId, Long studentId) {
        if (!classQuery.findStudentIds(classId).contains(studentId)) {
            throw new ForbiddenException("该学生不在指定班级中");
        }
    }

    public void requireClassAndStudent(Long classId, Long studentId) {
        requireClassTeacher(classId);
        requireStudentInClass(classId, studentId);
    }

    /** Explicit, request-context-free authorization seam for report workers and notice queries. */
    public boolean canActorAccessClass(Long actorId, ReportActorSnapshot actor, Long classId) {
        if (!isCurrentActiveActor(actorId, actor) || classId == null) return false;
        ClassQuery.ClassInfo info = classQuery.findById(classId).orElse(null);
        if (info == null) return false;
        return actor.admin() || actor.teacher() && actorId.equals(info.teacherId());
    }

    /** Explicit, request-context-free authorization seam including current student membership. */
    public boolean canActorAccessStudent(Long actorId, ReportActorSnapshot actor,
                                         Long classId, Long studentId) {
        return studentId != null
                && canActorAccessClass(actorId, actor, classId)
                && classQuery.findStudentIds(classId).contains(studentId);
    }

    private boolean isCurrentActiveActor(Long actorId, ReportActorSnapshot actor) {
        return actorId != null && actor != null && actorId.equals(actor.userId()) && actor.active()
                && (actor.admin() || actor.teacher());
    }
}
