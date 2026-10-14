package top.everblue.oj.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import top.everblue.oj.system.entity.MetricsEvent;

import java.time.LocalDate;

/**
 * 前端体验埋点聚合事件 Mapper。
 * <p>
 * 聚合写入：按 (event_day, event_type, event_key) 唯一键当日累计，
 * 命中已存在记录时 count 累加（数据库原子操作，天然防并发重复计数）。
 */
@Mapper
public interface MetricsEventMapper extends BaseMapper<MetricsEvent> {

    @Insert("INSERT INTO metrics_event (event_day, event_type, event_key, count) " +
            "VALUES (#{day}, #{type}, #{key}, #{count}) " +
            "ON DUPLICATE KEY UPDATE count = count + VALUES(count)")
    int upsertCount(@Param("day") LocalDate day,
                    @Param("type") String type,
                    @Param("key") String key,
                    @Param("count") int count);
}
