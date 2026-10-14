package top.everblue.oj.teaching.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import top.everblue.oj.teaching.entity.ClassStudent;
import com.baomidou.mybatisplus.spring.service.IService;

import java.util.Map;

/**
 * 班级成员表 服务类
 *
 * @author RasVz
 * @since 2025-12-12
 */
public interface ClassStudentService extends IService<ClassStudent> {
    IPage<Map<String, Object>> getClassMembers(IPage<?> page, Long classId, String keyword);
}
