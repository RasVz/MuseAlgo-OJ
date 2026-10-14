package top.everblue.oj.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.everblue.oj.community.entity.UserFavorite;

/**
 * 收藏记录 Mapper，全部操作走 MyBatis-Plus 内置 CRUD，无自定义 SQL。
 * <p>
 * 取消收藏不删行而是把 {@code status} 置 0，因此删除一律用
 * {@code LambdaQueryWrapper} 改字段而非 {@code deleteById}——
 * 表的唯一索引建在 (user_id, target_id, target_type) 上而非主键上，
 * 按主键删会误伤同一用户对同一目标的软取消行。
 * </p>
 */
@Mapper
public interface UserFavoriteMapper extends BaseMapper<UserFavorite> {
}
