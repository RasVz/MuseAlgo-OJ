package top.everblue.oj.teaching.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Param;
import top.everblue.oj.teaching.entity.ClassStudent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Map;

/**
 * <p>
 * 班级成员表 Mapper 接口
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
public interface ClassStudentMapper extends BaseMapper<ClassStudent> {
    // 自定义分页查询，关联查询用户信息
    IPage<Map<String, Object>> selectClassMembers(IPage<?> page, @Param("classId") Long classId, @Param("keyword") String keyword);
}
