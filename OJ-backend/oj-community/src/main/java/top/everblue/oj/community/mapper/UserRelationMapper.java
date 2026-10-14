package top.everblue.oj.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import top.everblue.oj.community.entity.UserRelation;
import top.everblue.oj.community.entity.vo.UserRelationVO;

import java.util.List;

/**
 * 关注关系 Mapper。
 * <p>
 * {@code selectFollowingList} / {@code selectFollowerList} 的 SQL 在
 * {@code resources/mapper/UserRelationMapper.xml} 中，不在本文件：
 * 两条语句都要 {@code LEFT JOIN user_relation r2} 来自算「当前查看者是否关注了这一行」，
 * 注解 SQL 写多行 JOIN 可读性差。
 * </p>
 * <p>
 * 注意本接口<b>没有</b> {@code @Mapper} 注解，与本包其他 Mapper 不同：
 * 它依赖启动类上的 {@code @MapperScan("top.everblue.oj.community.mapper")}
 * 统一注册。加不加 {@code @Mapper} 都能工作，保持现状不改动。
 * </p>
 * <p>
 * 同理，注解方法与 XML 定义<b>不能同时存在</b>：MyBatis 会优先采用 XML，
 * 注解版本静默失效并打 ERROR 级别日志。新增方法前先看 XML 里有没有同名 id。
 * </p>
 */
public interface UserRelationMapper extends BaseMapper<UserRelation> {

    // 查询某人的关注列表 (含 isFollowed 状态)
    /**
     * 分页查询某人的关注列表（TA 关注了谁）。
     *
     * @param page     MyBatis-Plus 分页对象，SQL 定义在 XML 中
     * @param userId   被查看者的用户 ID
     * @param viewerId 当前查看者 ID，用于计算 {@code isFollowed}；与 {@code userId} 相同时全部为 true
     * @return 当前页的关注列表
     */
    Page<UserRelationVO> selectFollowingList(Page<?> page, @Param("userId") Long userId, @Param("viewerId") Long viewerId);

    // 查询某人的粉丝列表 (含 isFollowed 状态)
    /**
     * 分页查询某人的粉丝列表（谁关注了 TA）。
     *
     * @param page     MyBatis-Plus 分页对象，SQL 定义在 XML 中
     * @param userId   被查看者的用户 ID
     * @param viewerId 当前查看者 ID，用于计算 {@code isFollowed}
     * @return 当前页的粉丝列表
     */
    Page<UserRelationVO> selectFollowerList(Page<?> page, @Param("userId") Long userId, @Param("viewerId") Long viewerId);

    /**
     * 取出某用户关注的所有 ID，供批量回填 {@code isFollowed} 标记。
     * <p>
     * 用这个而不是逐个调用上面的分页查询：后者会按行数放大 SQL 次数。
     * </p>
     *
     * @return 被关注的用户 ID 列表；未关注任何人时返回空集合
     */
    @Select("SELECT followed_id FROM user_relation WHERE follower_id = #{followerId}")
    List<Long> selectFollowedIds(Long followerId);
}