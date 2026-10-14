package top.everblue.oj.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 前端体验埋点聚合事件（匿名，无用户关联）。
 * <p>
 * 聚合式设计：按 (eventDay, eventType, eventKey) 当日 upsert 计数，
 * 对应 Flyway V49 迁移 {@code metrics_event} 表。
 *
 * @see top.everblue.oj.system.controller.MetricsController
 */
@Data
@TableName("metrics_event")
public class MetricsEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 统计日（自然日） */
    private LocalDate eventDay;

    /** 事件类型：route / route_slow / api_fail / js_error */
    private String eventType;

    /** 事件对象：路由名 / 接口路径 / 错误消息(前 200 字) */
    private String eventKey;

    /** 当日累计次数 */
    private Integer count;

    /** 首次记录时间 */
    private LocalDateTime createdAt;
}
