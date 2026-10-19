package top.everblue.oj.teaching.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.teaching.entity.Class;
import top.everblue.oj.teaching.entity.ClassStudent;
import top.everblue.oj.teaching.service.ClassService;
import top.everblue.oj.teaching.service.ClassStudentService;

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
            throw new IllegalStateException("备注更新失败");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeMember(Long classId, Long studentId) {
        requireClassManager(classId);
        if (!classStudentService.remove(new LambdaQueryWrapper<ClassStudent>()
                .eq(ClassStudent::getClassId, classId)
                .eq(ClassStudent::getStudentId, studentId))) {
            throw new IllegalStateException("成员不存在或移除失败");
        }
    }

    /** 返回是否可管理；学生只能读取自己班级的可见目录。 */
    public boolean requireClassReader(Long classId) {
        Class info = classService.getById(classId);
        if (info == null) throw new IllegalArgumentException("班级不存在");
        Long actor = SecurityUtils.getUserId();
        if (SecurityUtils.isAdmin() || (SecurityUtils.isTeacherOrAdmin() && actor != null && actor.equals(info.getTeacherId()))) return true;
        if (actor != null && classStudentService.count(new LambdaQueryWrapper<ClassStudent>()
                .eq(ClassStudent::getClassId, classId).eq(ClassStudent::getStudentId, actor)) > 0) return false;
        throw new top.everblue.oj.common.exception.ForbiddenException("无权查看该班级课件");
    }

    public void requireClassManager(Long classId) {
        SecurityUtils.requireTeacherOrAdmin();
        Class classInfo = classService.getById(classId);
        if (classInfo == null) {
            throw new IllegalArgumentException("班级不存在");
        }
        SecurityUtils.requireOwnerOrAdmin(classInfo.getTeacherId());
    }

    private ClassStudent findMembership(Long classId, Long studentId) {
        ClassStudent membership = classStudentService.getOne(new LambdaQueryWrapper<ClassStudent>()
                .eq(ClassStudent::getClassId, classId)
                .eq(ClassStudent::getStudentId, studentId));
        if (membership == null) {
            throw new IllegalArgumentException("成员不存在");
        }
        return membership;
    }
}
