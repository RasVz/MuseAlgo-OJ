package top.everblue.oj.teaching.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import top.everblue.oj.teaching.entity.ClassStudent;
import top.everblue.oj.teaching.mapper.ClassStudentMapper;
import top.everblue.oj.teaching.service.ClassStudentService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * <p>
 * 班级成员表 服务实现类
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
@Service
public class ClassStudentServiceImpl extends ServiceImpl<ClassStudentMapper, ClassStudent> implements ClassStudentService {
    @Override
    public IPage<Map<String, Object>> getClassMembers(IPage<?> page, Long classId, String keyword) {
        return this.baseMapper.selectClassMembers(page, classId, keyword);
    }
}
