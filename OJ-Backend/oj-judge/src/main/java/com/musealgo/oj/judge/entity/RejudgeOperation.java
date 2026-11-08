package com.musealgo.oj.judge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 受控重判操作（T8）。一次管理员请求一行，item 为实际提交条目。 */
@Getter
@Setter
@TableName("rejudge_operation")
public class RejudgeOperation implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String SCOPE_SINGLE_SUBMISSION = "SINGLE_SUBMISSION";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("operator_id")
    private Long operatorId;
    @TableField("operator_name")
    private String operatorName;
    private String reason;
    @TableField("scope_type")
    private String scopeType;
    @TableField("scope_json")
    private String scopeJson;
    private String status;
    @TableField("total_items")
    private Integer totalItems;
    @TableField("succeeded_items")
    private Integer succeededItems;
    @TableField("failed_items")
    private Integer failedItems;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("started_at")
    private LocalDateTime startedAt;
    @TableField("finished_at")
    private LocalDateTime finishedAt;
    @TableField("error_message")
    private String errorMessage;
}
