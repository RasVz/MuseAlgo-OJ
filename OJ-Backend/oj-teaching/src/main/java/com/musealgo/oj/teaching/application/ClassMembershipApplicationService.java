package com.musealgo.oj.teaching.application;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.teaching.error.TeachingErrorCode;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.teaching.entity.Class;
import com.musealgo.oj.teaching.entity.ClassStudent;
import com.musealgo.oj.teaching.service.ClassService;
import com.musealgo.oj.teaching.service.ClassStudentService;

import java.util.Map;

/** Authorization boundary for class roster administration. */
@Service
public class ClassMembershipApplicationService {

    private final ClassService classService;
    private final ClassStudentService classStudentService;

    public ClassMembershipApplicationService(ClassService classService,
                                             ClassStudentService classStudentService) {
        this.classService = classService;
        this.classStudentService = classStudentService;
    }

    public IPage<Map<String, Object>> getMembers(int page, int size, Long classId, String keyword) {
        requireClassManager(classId);
        return classStudentService.getClassMembers(new Page<>(page, size), classId, keyword);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateRemark(Long classId, Long studentId, String remark) {
        requireClassManager(classId);
        ClassStudent membership = findMembership(classId, studentId);
        membership.setRemark(remark);
        if (!classStudentService.updateById(membership)) {
            throw new BusinessException(TeachingErrorCode.TEACHING_WRITE_FAILED, "备注更新失败");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeMember(Long classId, Long studentId) {
        requireClassManager(classId);
        if (classService.getByIdForUpdate(classId) == null)
            throw new BusinessException(TeachingErrorCode.CLASS_NOT_FOUND);
        if (!classStudentService.remove(new LambdaQueryWrapper<ClassStudent>()
                .eq(ClassStudent::getClassId, classId)
                .eq(ClassStudent::getStudentId, studentId))) {
            throw new BusinessException(TeachingErrorCode.CLASS_MEMBER_NOT_FOUND, "成员不存在");
        }
        classService.refreshStudentCount(classId);
    }

    /** 返回是否可管理；学生只能读取自己班级的可见目录。 */
    public boolean requireClassReader(Long classId) {
        Class info = classService.getById(classId);
        if (info == null) throw new BusinessException(TeachingErrorCode.CLASS_NOT_FOUND, "班级不存在");
        Long actor = SecurityUtils.getUserId();
        if (SecurityUtils.isAdmin() || (SecurityUtils.isTeacherOrAdmin() && actor != null && actor.equals(info.getTeacherId()))) return true;
        if (actor != null && classStudentService.count(new LambdaQueryWrapper<ClassStudent>()
                .eq(ClassStudent::getClassId, classId).eq(ClassStudent::getStudentId, actor)) > 0) return false;
        throw new com.musealgo.oj.common.exception.ForbiddenException("无权查看该班级");
    }

    public void requireClassManager(Long classId) {
        SecurityUtils.requireTeacherOrAdmin();
        Class classInfo = classService.getById(classId);
        if (classInfo == null) {
            throw new BusinessException(TeachingErrorCode.CLASS_NOT_FOUND, "班级不存在");
        }
        SecurityUtils.requireOwnerOrAdmin(classInfo.getTeacherId());
    }

    private ClassStudent findMembership(Long classId, Long studentId) {
        ClassStudent membership = classStudentService.getOne(new LambdaQueryWrapper<ClassStudent>()
                .eq(ClassStudent::getClassId, classId)
                .eq(ClassStudent::getStudentId, studentId));
        if (membership == null) {
            throw new BusinessException(TeachingErrorCode.CLASS_MEMBER_NOT_FOUND, "成员不存在");
        }
        return membership;
    }
}
