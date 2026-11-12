package com.musealgo.oj.problem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 批量题面整理任务（异步，服务端持有进度）。 */
@Getter
@Setter
@TableName("problem_format_job")
public class ProblemFormatJob {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long creatorId;

    /** PENDING / RUNNING / DONE / CANCELED / FAILED */
    private String status;

    private Boolean ipMode;

    private Boolean autoApply;

    private Integer total;

    private Integer applied;

    private Integer review;

    private Integer failed;

    private Integer skipped;

    private Boolean cancelRequested;

    private String message;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private LocalDateTime finishTime;
}
