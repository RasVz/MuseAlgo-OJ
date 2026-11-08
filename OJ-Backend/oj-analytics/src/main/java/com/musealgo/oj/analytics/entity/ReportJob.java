package com.musealgo.oj.analytics.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** T9-2 报告生成任务。 */
@Data
@TableName("report_job")
public class ReportJob {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long studentId;
    private String studentName;
    private Long classId;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String status;
    private String htmlContent;
    private String errorMessage;
    private Integer attempts;
    private Long requesterId;
    private Long activeRequesterId;
    private Long generationNo;
    private Long terminalRevision;
    private Long snapshotId;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private LocalDateTime heartbeatAt;
    private LocalDateTime nextAttemptAt;
    private String lastErrorCode;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime finishedAt;
}
