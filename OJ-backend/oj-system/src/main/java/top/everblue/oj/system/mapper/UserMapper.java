package top.everblue.oj.system.mapper;

import org.apache.ibatis.annotations.Select;
import top.everblue.oj.system.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

/**
 * <p>
 * 用户表 Mapper 接口
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
public interface UserMapper extends BaseMapper<User> {

    @Select("SELECT id FROM `user` WHERE role = 3 AND COALESCE(is_banned, 0) <> 1 ORDER BY id FOR UPDATE")
    List<Long> lockEnabledAdminIds();
}
