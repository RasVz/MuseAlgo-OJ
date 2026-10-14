package top.everblue.oj.community.mapper;

import org.apache.ibatis.annotations.*;

/**
 * 动态表情回应（reaction）Mapper。
 * <p>
 * 这里刻意<b>不</b>继承 {@code BaseMapper}、也不建对应实体类：
 * reaction 的读写只用到「按用户+动态查一行 / 插一行 / 改一行 / 删一行 / 计数」，
 * 且每条 SQL 都必须带 {@code post_id + user_id} 条件（表上有
 * {@code uk_post_reaction_post_user} 唯一键，一行对应一人对一条动态的回应）。
 * 用 {@code BaseMapper} 的按主键操作在这张表上语义不成立，
 * 留着只会诱导后来者以为可以按 {@code id} 改删。故全部走显式注解 SQL。
 * </p>
 * <p>
 * {@code reaction_type} 用字符串而非枚举落库：取值集合由前端表情面板决定，
 * 后端只做透传与计数，不做枚举校验，新增表情无需改代码。
 * </p>
 */
@Mapper
public interface PostReactionMapper {

    /**
     * 查询某用户对某条动态的回应表情。
     *
     * @return 回应类型字符串；未回应过返回 {@code null}
     */
    @Select("SELECT reaction_type FROM post_reaction WHERE post_id = #{postId} AND user_id = #{userId} LIMIT 1")
    String selectUserReaction(@Param("postId") Long postId, @Param("userId") Long userId);

    /**
     * 首次回应：插入一行。
     *
     * @return 影响行数，正常为 1；同一用户重复插入会触发唯一约束冲突，由 Service 层兜底为「改为更新」
     */
    @Insert("INSERT INTO post_reaction (post_id, user_id, reaction_type, create_time, update_time) VALUES (#{postId}, #{userId}, #{reactionType}, NOW(), NOW())")
    int insertReaction(@Param("postId") Long postId,
                       @Param("userId") Long userId,
                       @Param("reactionType") String reactionType);

    /**
     * 改选表情（同一用户已回应过）。
     *
     * @return 影响行数，为 0 表示该用户此前没有回应记录，调用方需回落到插入
     */
    @Update("UPDATE post_reaction SET reaction_type = #{reactionType}, update_time = NOW() WHERE post_id = #{postId} AND user_id = #{userId}")
    int updateReaction(@Param("postId") Long postId,
                       @Param("userId") Long userId,
                       @Param("reactionType") String reactionType);

    /**
     * 取消回应：物理删除行，因为回应本身没有「历史状态」语义。
     *
     * @return 影响行数，0 表示本来就没有回应
     */
    @Delete("DELETE FROM post_reaction WHERE post_id = #{postId} AND user_id = #{userId}")
    int deleteReaction(@Param("postId") Long postId, @Param("userId") Long userId);

    /**
     * 统计某条动态下指定表情的回应人数。
     *
     * @return 计数结果，无人回应时为 0
     */
    @Select("SELECT COUNT(*) FROM post_reaction WHERE post_id = #{postId} AND reaction_type = #{reactionType}")
    long countByPostIdAndReaction(@Param("postId") Long postId, @Param("reactionType") String reactionType);
}
