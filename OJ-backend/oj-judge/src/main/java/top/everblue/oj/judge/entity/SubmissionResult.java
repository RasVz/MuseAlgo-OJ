package top.everblue.oj.judge.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Normalized, one-to-one judged result for a submission. */
@Getter
@Setter
@TableName("submission_result")
public class SubmissionResult {
    @TableId("submission_id")
    private Long submissionId;
    @TableField("status")
    private Byte status;
    @TableField("score")
    private Integer score;
    @TableField("run_time")
    private Integer runTime;
    @TableField("run_memory")
    private Integer runMemory;
    @TableField("error_message")
    private String errorMessage;
    @TableField("judged_at")
    private LocalDateTime judgedAt;
}
