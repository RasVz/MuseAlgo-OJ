package com.musealgo.oj.problem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 题面版本快照。
 * <p>
 * 每次「应用 AI 结果 / 记录当前题面 / 启用历史版本」都会写入或切换这里的记录；
 * 正常发布（LIVE）与待审候选（CANDIDATE）共用一张表，靠 {@code live_problem_id}
 * 生成列的唯一索引保证一道题最多一个 LIVE。
 * </p>
 */
@Getter
@Setter
@TableName("problem_statement_version")
public class ProblemStatementVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long problemId;

    private Integer versionNo;

    /** LIVE / ARCHIVED / CANDIDATE / DISCARDED */
    private String status;

    /** INITIAL / MANUAL / AI / AI_APPLIED */
    private String source;

    private String title;

    private String content;

    private String inputDesc;

    private String outputDesc;

    private String hint;

    private Integer timeLimit;

    private Integer memoryLimit;

    private String samplesJson;

    private Long baseVersionId;

    private Long jobId;

    private String issuesJson;

    private String note;

    private Long createdBy;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
