package com.musealgo.oj.problem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 批量整理任务中的单题项。
 * <p>
 * {@code sourceJson} 与 {@code formattedJson} 是提案快照：教师稍后审阅时无需再次调用模型，
 * 既省成本也保证「看到的」与「将要应用的」是同一份内容。
 * </p>
 */
@Getter
@Setter
@TableName("problem_format_job_item")
public class ProblemFormatJobItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long jobId;

    private Long problemId;

    /** PENDING / RUNNING / APPLIED / REVIEW / FAILED / SKIPPED */
    private String status;

    private String title;

    private String message;

    private String errorCode;

    private String sourceJson;

    private String formattedJson;

    private String issuesJson;

    private String baseHash;

    /** 单题版本优化时为基线版本；批量模式为空 */
    private Long baseVersionId;

    /** 版本优化产出的候选版本 */
    private Long resultVersionId;

    private Integer attempts;

    private String appliedFields;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
