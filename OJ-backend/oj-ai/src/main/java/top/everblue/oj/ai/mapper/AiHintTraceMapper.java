package top.everblue.oj.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import top.everblue.oj.ai.entity.AiHintTrace;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * ai_hint_trace 轨迹表 Mapper（单表插入/聚合，无需 XML）。
 * <p>
 * 跨模块读轨迹必须走 oj-api 的 {@code HintTraceQuery} 契约，禁止其他模块直接注入本 Mapper。
 * </p>
 */
@Mapper
public interface AiHintTraceMapper extends BaseMapper<AiHintTrace> {

    /** 按题目批量查询每个用户在轨迹表中的最高解锁级别（无轨迹的用户不出现在结果中） */
    @Select("<script>SELECT user_id AS userId, MAX(level) AS maxLevel " +
            "FROM ai_hint_trace WHERE problem_id = #{problemId} AND user_id IN " +
            "<foreach collection='userIds' item='uid' open='(' separator=',' close=')'>#{uid}</foreach> " +
            "GROUP BY user_id</script>")
    List<Map<String, Object>> selectMaxLevelByUsers(@Param("problemId") Long problemId,
                                                    @Param("userIds") Collection<Long> userIds);

    /** 按题目统计各级别解锁人数（level → 解锁到该级别的用户数） */
    @Select("SELECT level AS level, COUNT(*) AS cnt FROM ai_hint_trace " +
            "WHERE problem_id = #{problemId} GROUP BY level")
    List<Map<String, Object>> selectCountByLevel(@Param("problemId") Long problemId);
}
