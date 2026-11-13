package com.musealgo.oj.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 题面生成草稿（V75 迁移）。
 * <p>
 * 一次生成受理可产生 1~3 行（同 {@code groupId}、{@code variantNo} 递增），
 * 每行独立走状态机：这样单方案失败可以单独重试，方案之间也不会互相阻塞。
 * </p>
 */
@Getter
@Setter
@TableName("problem_draft")
public class ProblemDraft implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("creator_id")
    private Long creatorId;

    @TableField("group_id")
    private String groupId;

    @TableField("variant_no")
    private Integer variantNo;

    /** PENDING / RUNNING / READY / FAILED / CANCELED */
    private String status;

    /** QUEUED / PROMPTING / GENERATING / PARSING / DONE */
    private String phase;

    @TableField("brief_json")
    private String briefJson;

    @TableField("brief_summary")
    private String briefSummary;

    private String instruction;

    @TableField("target_field")
    private String targetField;

    @TableField("parent_id")
    private Long parentId;

    private String title;

    @TableField("content_json")
    private String contentJson;

    @TableField("issues_json")
    private String issuesJson;

    @TableField("error_code")
    private String errorCode;

    private String message;

    @TableField("problem_id")
    private Long problemId;

    @TableField("saved_fields")
    private String savedFields;

    @TableField("prompt_tokens")
    private Integer promptTokens;

    @TableField("completion_tokens")
    private Integer completionTokens;

    @TableField("latency_ms")
    private Integer latencyMs;

    private Integer attempts;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableField("finish_time")
    private LocalDateTime finishTime;
}
