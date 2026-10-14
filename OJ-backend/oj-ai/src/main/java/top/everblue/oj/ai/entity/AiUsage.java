package top.everblue.oj.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 调用用量流水（ai_usage 表，V19 迁移）。
 * 成本核算、配额审计与行为分析的统一数据源。
 */
@Getter
@Setter
@TableName("ai_usage")
public class AiUsage implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAIL = "FAIL";
    public static final String STATUS_QUOTA_REJECTED = "QUOTA_REJECTED";
    public static final String STATUS_CONTEST_BLOCKED = "CONTEST_BLOCKED";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("problem_id")
    private Long problemId;

    /** 场景：ASK=题目答疑，预留 REVIEW/PLAN 等 */
    @TableField("scene")
    private String scene;

    @TableField("model")
    private String model;

    @TableField("prompt_tokens")
    private Integer promptTokens;

    @TableField("completion_tokens")
    private Integer completionTokens;

    @TableField("total_tokens")
    private Integer totalTokens;

    /** 1=按字符估算，0=API 返回精确值 */
    @TableField("tokens_estimated")
    private Boolean tokensEstimated;

    @TableField("latency_ms")
    private Long latencyMs;

    /** SUCCESS / FAIL / QUOTA_REJECTED / CONTEST_BLOCKED */
    @TableField("status")
    private String status;

    @TableField("detail")
    private String detail;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
