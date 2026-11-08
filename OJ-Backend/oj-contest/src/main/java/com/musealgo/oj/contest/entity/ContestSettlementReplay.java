package com.musealgo.oj.contest.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** T8-3 C：结算链式重放审计记录。 */
@Data
@TableName("contest_settlement_replay")
public class ContestSettlementReplay {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long fromContestId;
    private Long operatorId;
    private String reason;
    private String status;
    private Integer totalContests;
    private Integer affectedUsers;
    private String backupJson;
    private Long restoredBy;
    private LocalDateTime restoredAt;
    private String restoreReason;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String errorMessage;
    private LocalDateTime createTime;
}
