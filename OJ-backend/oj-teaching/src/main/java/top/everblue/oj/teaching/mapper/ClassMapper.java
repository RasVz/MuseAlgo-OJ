package top.everblue.oj.teaching.mapper;

import top.everblue.oj.teaching.entity.Class;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 * 班级表 Mapper 接口
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
public interface ClassMapper extends BaseMapper<Class> {
    IPage<Class> selectJoinedClasses(Page<?> page, @Param("userId") Long userId);
}
