package top.everblue.oj.teaching.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.everblue.oj.teaching.entity.ClassCourseware;
import top.everblue.oj.teaching.entity.ClassStudent;
import top.everblue.oj.teaching.entity.Courseware;
import top.everblue.oj.teaching.mapper.ClassCoursewareMapper;
import top.everblue.oj.teaching.service.ClassCoursewareService;
import top.everblue.oj.teaching.service.ClassStudentService;
import top.everblue.oj.teaching.service.CoursewareService;

import java.util.List;

/**
 * 班级-课件关联服务：维护「哪份课件被哪些班级引用、按什么顺序展示」。
 * <p>
 * <strong>关联记录与课件本体分属两张表</strong>，所以「添加课件到班级」实际是两件事：
 * 先按 url 找到或新建课件，再建关联行。课件可以被多个班级共享，
 * 因此不能简单地「加一次就插一份课件」，那会产生大量重复数据。
 * 用 url 作为课件的自然键来判重，也顺带保证了重复添加同一个 url 不会产出两份课件。
 * </p>
 * <p>
 * 排序号取「当前最大 sort + 1」而非时间戳或主键：班级侧的课件顺序是教师手动编排的结果，
 * 追加到末尾即可，不需要在中间插入时重排（那会带来大量行更新）。
 * 并发添加时可能出现两条相同 sort——展示层应按 sort 再按 id 兜底排序，不要假设它唯一。
 * </p>
 * <p>
 * {@link #isVisibleForMember(Long, Long)} 是可见性判定的唯一入口，
 * 语义比「是否在班级里」多一层：课件必须<strong>先被绑定且标记为可见</strong>，再要求用户是该班学生。
 * 只查绑定关系会漏掉教师手动隐藏的课件——{@code isVisible} 正是为这个场景存在的。
 * 注意此方法是逐条查询的，批量判断可见性时应在调用方收集 coursewareId 后统一处理，避免 N+1。
 * </p>
 * <p>
 * 已知短板：新建课件时 {@code teacherId} 目前是写死的常量，尚未接入 SecurityContext，
 * 因此<strong>列表里的「创建老师」信息对非教师用户不可信</strong>。补齐时应改为从
 * {@code SecurityUtils.getUserId()} 取当前登录教师，不要沿用这个常量。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class ClassCoursewareServiceImpl
        extends com.baomidou.mybatisplus.extension.service.impl.ServiceImpl<ClassCoursewareMapper, ClassCourseware>
        implements ClassCoursewareService {

    private final CoursewareService coursewareService;
    private final ClassStudentService classStudentService;

    @Override
    public boolean isVisibleForMember(Long coursewareId, Long userId) {
        List<ClassCourseware> binds = this.list(new LambdaQueryWrapper<ClassCourseware>()
                .eq(ClassCourseware::getCoursewareId, coursewareId)
                .eq(ClassCourseware::getIsVisible, true));
        if (binds.isEmpty()) {
            return false;
        }
        List<Long> classIds = binds.stream()
                .map(ClassCourseware::getClassId)
                .distinct()
                .toList();
        return classStudentService.count(new LambdaQueryWrapper<ClassStudent>()
                .in(ClassStudent::getClassId, classIds)
                .eq(ClassStudent::getStudentId, userId)) > 0;
    }

    @Override
    @Transactional
    public void addCoursewareToClass(Long classId, String title, String url,
                                     Long problemListId, String description) {
        // 1. 根据 url 查找或创建课件
        Courseware cw = coursewareService.getOne(
                new LambdaQueryWrapper<Courseware>().eq(Courseware::getUrl, url));
        Long coursewareId;
        if (cw != null) {
            coursewareId = cw.getId();
        } else {
            Courseware ncw = new Courseware();
            ncw.setTitle(title); ncw.setUrl(url);
            ncw.setProblemListId(problemListId); ncw.setDescription(description);
            ncw.setTeacherId(1L); // TODO: SecurityContext
            coursewareService.save(ncw);
            coursewareId = ncw.getId();
        }

        // 2. 去重
        long count = this.count(new LambdaQueryWrapper<ClassCourseware>()
                .eq(ClassCourseware::getClassId, classId)
                .eq(ClassCourseware::getCoursewareId, coursewareId));
        if (count > 0) throw new IllegalStateException("该课件已添加到班级");

        // 3. 计算排序号
        ClassCourseware last = this.getOne(new LambdaQueryWrapper<ClassCourseware>()
                .eq(ClassCourseware::getClassId, classId)
                .orderByDesc(ClassCourseware::getSort).last("LIMIT 1"));
        int sort = (last != null && last.getSort() != null) ? last.getSort() + 1 : 1;

        // 4. 创建关联
        ClassCourseware rel = new ClassCourseware();
        rel.setClassId(classId); rel.setCoursewareId(coursewareId);
        rel.setSort(sort); rel.setIsVisible(true);
        this.save(rel);
    }
}
