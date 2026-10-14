package top.everblue.oj.analytics.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import top.everblue.oj.analytics.entity.UserTagRating;

import java.util.List;

/**
 * 用户-标签 Rating Mapper：AC 后更新各标签评分，并按标签集合回读。
 * <p>
 * {@code user_tag_rating} 以 (user_id, tag_id) 为联合主键，实体上<strong>刻意没加 {@code @TableId}</strong>：
 * MyBatis-Plus 的主键能力在联合主键下语义不成立，会生成只按单列匹配的 update/delete，
 * 把一个标签的评分写到另一个标签上。实际用法只有 {@code selectList}、{@code upsert} 与 XML 自定义 SQL。
 * <strong>请不要顺手补注解</strong>，需要按 (userId, tagId) 定位时在 XML 里显式写两个条件。
 * </p>
 * <p>
 * {@link #upsert} 是 MySQL 的 {@code INSERT ... ON DUPLICATE KEY UPDATE}：
 * 一次提交往往涉及多个标签，逐条「先查再写」会在并发提交同一用户时互相覆盖，
 * 而 ON DUPLICATE 依赖数据库唯一索引天然幂等，是这里更稳的选择。
 * </p>
 * <p>
 * 注意本接口的 SQL 定义在 XML（{@code UserTagRatingMapper.xml}）中而非注解上，
 * 因此其中的 {@code <}、{@code >} 必须按 XML 规则写成 {@code &lt;} {@code &gt;}。
 * MyBatis 同一个 mapper id 不能同时存在注解与 XML 定义，重复定义会打 ERROR 并让注解失效。
 * </p>
 */
public interface UserTagRatingMapper extends BaseMapper<UserTagRating> {

    /**
     * 批量 upsert：AC 后更新所有相关 tag 的评分。
     * MySQL: INSERT ... ON DUPLICATE KEY UPDATE
     */
    int upsert(@Param("userId") Long userId,
               @Param("tagId") Long tagId,
               @Param("rating") int rating);

    /**
     * 查询用户在指定 tag 列表上的评分（包括 problem_count = 0 的返回默认 800）。
     */
    List<UserTagRating> selectByUserAndTags(@Param("userId") Long userId,
                                            @Param("tagIds") List<Long> tagIds);
}
