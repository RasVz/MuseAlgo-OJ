package top.everblue.oj.analytics.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.everblue.oj.analytics.entity.ProblemTag;

/**
 * 题目-标签关联 Mapper。
 * <p>
 * 本接口<strong>刻意是空的</strong>：{@code problem_tag} 是 (problem_id, tag_id) 联合主键的纯关联表，
 * 实体上故意没加 {@code @TableId}，因为 MyBatis-Plus 的主键相关能力（selectById / updateById /
 * deleteById）在联合主键下语义不成立，误加反而会生成只按一列匹配的 SQL，导致跨标签误更新。
 * 实际用法只有 {@code selectList} 与 XML 里的自定义 JOIN 查询，不需要主键。
 * </p>
 * <p>
 * <strong>请不要顺手补上 {@code @TableId}</strong>。真的需要按单个 id 改数据时，
 * 正确做法是在 XML 里显式写 {@code WHERE problem_id = ? AND tag_id = ?}。
 * </p>
 */
@Mapper
public interface ProblemTagMapper extends BaseMapper<ProblemTag> {
}