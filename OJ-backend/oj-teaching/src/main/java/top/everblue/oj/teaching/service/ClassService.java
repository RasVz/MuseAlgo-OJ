package top.everblue.oj.teaching.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import top.everblue.oj.teaching.entity.Class;


/**
 * 班级表 服务类
 */
public interface ClassService extends IService<Class> {
    boolean createClass(Class classGroup);

    String createInviteCode(Long classId);

    Integer joinByInviteCode(Long userId, String code);

    IPage<Class> getJoinedClasses(Integer page, Integer size, Long userId);

    /** 获取学生所有班级的作业列表（含班级名称） */
}
