package com.musealgo.oj.analytics.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** T9-2 报告交付记录。 */
@Data
@TableName("report_delivery")
public class ReportDelivery {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long jobId;
    private String channel;
    private Long recipientId;
    private String status;
    private Integer attempts;
    private String errorMessage;
    private LocalDateTime deliveredAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
