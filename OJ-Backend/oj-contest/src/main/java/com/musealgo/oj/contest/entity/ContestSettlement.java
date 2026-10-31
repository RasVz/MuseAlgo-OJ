package com.musealgo.oj.contest.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 比赛结算版本记录（T8-3 D）：历史成绩与其算法版本并存，不因算法升级被静默重算。 */
@Data
@TableName("contest_settlement")
public class ContestSettlement {
    @TableId(type = IdType.INPUT)
    private Long contestId;
    private String algorithmVersion;
    private String ratingAlgorithmVersion;
    private Integer settlementNo;
    private LocalDateTime finalizedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
