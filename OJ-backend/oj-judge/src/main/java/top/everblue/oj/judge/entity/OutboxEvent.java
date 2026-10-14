package top.everblue.oj.judge.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Outbox 事件表 —— 可靠消息投递的事务性保证。
 * <p>
 * 判题完成后，在同一数据库事务中写入 outbox_event 和更新 judge_task 状态。
 * OutboxPublisher 定时轮询 PENDING 事件投递到 RabbitMQ，确保 "至少一次" 送达。
 * </p>
 */
@Getter
@Setter
@TableName("outbox_event")
public class OutboxEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** UUID 主键 */
    @TableId("id")
    private String id;

    /** 聚合类型（如 "submission"） */
    @TableField("aggregate_type")
    private String aggregateType;

    /** 聚合根 ID（如 submissionId） */
    @TableField("aggregate_id")
    private Long aggregateId;

    /** 事件类型（如 "submission.judged"） */
    @TableField("event_type")
    private String eventType;

    /** 事件负载（JSON） */
    @TableField("payload")
    private String payload;

    /** PENDING / PUBLISHED / FAILED */
    @TableField("status")
    private String status;

    /** 已重试次数 */
    @TableField("retry_count")
    private Integer retryCount;

    /** 下次尝试时间 */
    @TableField("next_attempt_at")
    private LocalDateTime nextAttemptAt;

    /** 发布时间 */
    @TableField("published_at")
    private LocalDateTime publishedAt;

    /** 最后一次错误 */
    @TableField("last_error")
    private String lastError;

    @TableField("created_at")
    private LocalDateTime createdAt;

    // ---- 常量 ----
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String AGGREGATE_SUBMISSION = "submission";
    public static final String EVENT_SUBMISSION_JUDGED = "submission.judged";
}
