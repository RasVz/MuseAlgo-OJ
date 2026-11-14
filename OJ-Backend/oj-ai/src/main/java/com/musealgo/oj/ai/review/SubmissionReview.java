package com.musealgo.oj.ai.review;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 一行对应一次用户明确发起的生成；失败与追问不会覆盖旧记录。 */
@Data
@TableName("ai_submission_review")
public class SubmissionReview {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long submissionId;
    private Long problemId;
    private String requestId;
    private String requestHash;
    private Long parentReviewId;
    private String relationType;
    private String status;
    private String problemTitle;
    private String language;
    private Byte submissionStatus;
    private Integer score;
    private Integer runTime;
    private Integer runMemory;
    private String helpMode;
    private String studentQuestion;
    @TableField(select = false)
    private String inputJson;
    private String codeHash;
    private Boolean privileged;
    @TableField(select = false)
    private String answer;
    private String errorCode;
    private String message;
    private String executionId;
    private String model;
    private String serviceName;
    @TableField(select = false)
    private String sourceJson;
    private Integer promptTokens;
    private Integer completionTokens;
    private Boolean tokensEstimated;
    private Long latencyMs;
    private LocalDateTime createTime;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private LocalDateTime updateTime;
}
