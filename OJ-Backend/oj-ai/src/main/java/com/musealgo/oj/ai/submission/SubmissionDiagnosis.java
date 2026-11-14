package com.musealgo.oj.ai.submission;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 一条提交记录对应一份一次性 AI 诊断。 */
@Data
@TableName("submission_ai_diagnosis")
public class SubmissionDiagnosis {
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    @TableId(value = "submission_id", type = IdType.INPUT)
    private Long submissionId;
    private Long userId;
    private String status;
    private String executionId;
    private String content;
    private String errorCode;
    private String message;
    private String model;
    private String serviceName;
    private Integer promptTokens;
    private Integer completionTokens;
    private Boolean tokensEstimated;
    private Long latencyMs;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
