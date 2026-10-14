package top.everblue.oj.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import top.everblue.oj.ai.entity.AiUsage;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Collection;

/** ai_usage 流水表 Mapper（单表插入，无需 XML） */
@Mapper
public interface AiUsageMapper extends BaseMapper<AiUsage> {

    /** 管理台：时间窗内按状态汇总调用数与 token */
    @Select("SELECT status, COUNT(*) AS cnt, COALESCE(SUM(total_tokens),0) AS tokens " +
            "FROM ai_usage WHERE created_at >= #{from} AND created_at <= #{to} GROUP BY status")
    List<Map<String, Object>> selectStatusSummary(@Param("from") LocalDateTime from,
                                                   @Param("to") LocalDateTime to);

    /** 管理台：按日趋势（调用数 + token） */
    @Select("SELECT DATE(created_at) AS day, COUNT(*) AS calls, COALESCE(SUM(total_tokens),0) AS tokens " +
            "FROM ai_usage WHERE created_at >= #{from} AND created_at <= #{to} " +
            "GROUP BY DATE(created_at) ORDER BY day ASC")
    List<Map<String, Object>> selectDailyTrend(@Param("from") LocalDateTime from,
                                                @Param("to") LocalDateTime to);

    /** 管理台：用户 token 消耗排行 */
    @Select("SELECT user_id, COUNT(*) AS calls, COALESCE(SUM(total_tokens),0) AS tokens " +
            "FROM ai_usage WHERE created_at >= #{from} AND created_at <= #{to} " +
            "GROUP BY user_id ORDER BY tokens DESC LIMIT #{limit}")
    List<Map<String, Object>> selectUserRanking(@Param("from") LocalDateTime from,
                                                @Param("to") LocalDateTime to,
                                                @Param("limit") int limit);

    @Select("<script>SELECT user_id AS userId, COALESCE(SUM(total_tokens),0) AS tokens " +
            "FROM ai_usage WHERE user_id IN " +
            "<foreach collection='userIds' item='uid' open='(' separator=',' close=')'>#{uid}</foreach> " +
            "GROUP BY user_id</script>")
    List<Map<String, Object>> selectTokenUsageByUsers(@Param("userIds") Collection<Long> userIds);
}
