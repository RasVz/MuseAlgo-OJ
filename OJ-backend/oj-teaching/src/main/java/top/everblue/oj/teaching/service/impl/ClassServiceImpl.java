package top.everblue.oj.teaching.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import top.everblue.oj.teaching.entity.ClassStudent;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import top.everblue.oj.teaching.entity.Class;
import top.everblue.oj.teaching.mapper.ClassMapper;
import top.everblue.oj.teaching.mapper.ClassStudentMapper;
import top.everblue.oj.teaching.service.ClassService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 班级表 服务实现类
 */
@Service
@RequiredArgsConstructor
public class ClassServiceImpl extends ServiceImpl<ClassMapper, Class> implements ClassService {

    private final StringRedisTemplate redisTemplate;

    private final ClassStudentMapper classStudentMapper;

    // 创建班级
    @Override
    public boolean createClass(Class classGroup) {
        // 存入数据库
        return this.save(classGroup);
    }

    // 获取已经加入的班级
    @Override
    public IPage<Class> getJoinedClasses(Integer page, Integer size, Long userId) {
        Page<Class> pageInfo = new Page<>(page, size);
        return baseMapper.selectJoinedClasses(pageInfo, userId);
    }

    // 生成邀请码
    @Override
    public String createInviteCode(Long classId) {
        // 1. 生成 6 位随机数字
        String code = String.valueOf((int)((Math.random() * 9 + 1) * 100000));

        // 2. 存入 Redis，Key="invite:code:123456", Value="classId", 过期时间 5 分钟
        String key = "invite:code:" + code;
        redisTemplate.opsForValue().set(key, String.valueOf(classId), 5, TimeUnit.MINUTES);

        return code;
    }

    // 通过邀请码加入班级
    @Override
    public Integer joinByInviteCode(Long userId, String code) {
        // 1. 从 Redis 查询邀请码
        String key = "invite:code:" + code;
        String classIdStr = redisTemplate.opsForValue().get(key);

        if (classIdStr == null) {
            // 0 - "邀请码无效或已过期"
            return 0;
        }

        Long classId = Long.parseLong(classIdStr);

        // 2. 检查是否已经加入过 (防止重复加入)
        // 假设你有这个检查方法，如果没有请加上
        QueryWrapper<ClassStudent> query = new QueryWrapper<>();
        query.eq("class_id", classId);
        query.eq("student_id", userId);
        if (classStudentMapper.selectCount(query) > 0) {
            // 1 - "您已在班级中，无需重复加入"
            return 1;
        }

        // 3. 执行加入逻辑 (插入 class_student 表)
        ClassStudent classStudent = new ClassStudent();
        classStudent.setClassId(classId);
        classStudent.setStudentId(userId);
        classStudent.setJoinTime(LocalDateTime.now());
        classStudentMapper.insert(classStudent);

        // 2 - "加入班级成功"
        return 2;
    }
}
