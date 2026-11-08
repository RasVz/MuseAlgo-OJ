package com.musealgo.oj.judge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 判题任务表 —— 异步判题的持久化任务队列。
 * <p>
 * 每条记录对应一次 submission 的判题请求。
 * Worker 通过原子领取（SELECT ... FOR UPDATE SKIP LOCKED）获取待处理任务，
 * 支持失败重试（attempt_count + 指数退避）。
 * </p>
 * <p>
 * T8 起拆分为两个概念：{@code generation} 是执行代次（每领取一次 +1，用于 CAS/旧执行隔离），
 * {@code attempt_count} 只作失败重试预算（手动重判可清零，不影响代次保护）。
 * </p>
 */
@Getter
@Setter
@TableName("judge_task")
public class JudgeTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关联的 submission ID（唯一约束，幂等保障） */
    @TableField("submission_id")
    private Long submissionId;

    /** 任务状态：PENDING / PROCESSING / COMPLETED / FAILED */
    @TableField("status")
    private String status;

    /** 已失败重试次数（只作重试预算，不参与执行代次判断） */
    @TableField("attempt_count")
    private Integer attemptCount;

    /** 执行代次：每次 Worker 领取 +1；完成/失败 CAS 与阶段写入都用它识别旧执行。 */
    @TableField("generation")
    private Integer generation;

    /** 计划执行时间（首次 = 创建时间，重试 = 退避后时间） */
    @TableField("scheduled_at")
    private LocalDateTime scheduledAt;

    /** Worker 锁定时间 */
    @TableField("locked_at")
    private LocalDateTime lockedAt;

    /** 完成时间 */
    @TableField("completed_at")
    private LocalDateTime completedAt;

    /** 最后一次错误信息 */
    @TableField("last_error")
    private String lastError;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    // ---- 状态常量 ----
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
}
