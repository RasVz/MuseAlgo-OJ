package com.musealgo.oj.contest.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 比赛等级与 Rating 范围配置（T10-1）。
 * <p>下限含、上限不含；NULL 表示该侧不限。比赛创建时会把当前配置快照到 contest 行，
 * 之后修改本表不会追溯改变已有比赛。</p>
 */
@Data
@TableName("contest_level")
public class ContestLevel {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private Integer minRating;
    private Integer maxRating;
    private Boolean enabled;
    private Integer sortOrder;
    private String description;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
