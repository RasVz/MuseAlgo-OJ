package com.musealgo.oj.analytics.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable audit for one claimed report generation attempt. It never stores report content. */
@Data
@TableName("report_job_attempt")
public class ReportJobAttempt {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long jobId;
    private Integer attemptNo;
    private Long generationNo;
    private String triggerSource;
    private Long actorId;
    private String workerRef;
    private String stage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String outcome;
    private String errorCode;
    private String errorMessage;
}
