package top.everblue.oj.teaching.service;

import top.everblue.oj.teaching.entity.ClassCourseware;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 * 班级课件关联 服务类
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
public interface ClassCoursewareService extends IService<ClassCourseware> {

    /** 添加课件到班级（含去重、排序号计算），@Transactional 在实现层 */
    void addCoursewareToClass(Long classId, String title, String url, Long problemListId, String description);

    /**
     * 课件对某用户是否「班级内可见」：课件被至少一个可见班级关联，且用户是该班成员。
     * 用于私有课件 getDetail 的越权校验（学生学习场景放行）。
     */
    boolean isVisibleForMember(Long coursewareId, Long userId);
}
