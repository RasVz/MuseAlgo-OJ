package com.musealgo.oj.judge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 重判条目：一条提交一次重判记录，保存前值/后值/原因关联与用例摘要（T8）。 */
@Getter
@Setter
@TableName("rejudge_item")
public class RejudgeItem implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("operation_id")
    private Long operationId;
    @TableField("submission_id")
    private Long submissionId;
    @TableField("problem_id")
    private Long problemId;
    @TableField("user_id")
    private Long userId;
    @TableField("contest_id")
    private Long contestId;
    @TableField("vp_attempt_id")
    private Long vpAttemptId;
    @TableField("testcase_digest")
    private String testcaseDigest;
    @TableField("old_result_json")
    private String oldResultJson;
    @TableField("new_result_json")
    private String newResultJson;
    private String status;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("finished_at")
    private LocalDateTime finishedAt;
    @TableField("error_message")
    private String errorMessage;
}
