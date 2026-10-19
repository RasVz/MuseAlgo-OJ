package top.everblue.oj.contest.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 比赛实体，对应 contest 表。
 * <p>
 * 一场比赛的定义本体：赛程、计分模式、可见性与归属班级。业务上的三态流转
 * 「未开始 → 进行中 → 已结束」<b>不在此表存状态字段</b>，而是由
 * {@code startTime / endTime} 与当前时刻比较推导，属于派生状态。
 * 这样后台管理员手动改期时无需额外同步状态列，也不会出现状态与时间对不上的脏数据。
 * </p>
 *
 * <h3>比赛模式（{@code mode}）决定一切计分行为</h3>
 * <ul>
 *   <li>{@code ACM}：按「过题数 + 罚时」排名，OI 相关的分支不生效。</li>
 *   <li>{@code OI}：按各题历史最高分之和排名，同分并列，且比赛进行中对选手隐藏榜单。</li>
 * </ul>
 * 该字段在榜单计算（{@link top.everblue.oj.contest.service.RankRedisService}）、
 * 榜单可见性校验（{@link top.everblue.oj.contest.controller.ContestRankingSseController}）等多处被分支判断，
 * 取值拼写必须保持一致，读取侧统一用 {@code equalsIgnoreCase}。
 * </p>
 */
@Data
@TableName("contest")
public class Contest implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String title;
    private String description;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    private Integer duration; // 分钟

    private String type; // Official, Public

    private String mode; // ACM / OI（ADR-0003）

    private Long authorId;
    private String authorName;

    private Integer participantCount;

    private Integer visible;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;

    private Long classId;

    /** 赛后自动查重是否已执行（V48，0-未执行 1-已执行） */
    private Integer plagiarismChecked;
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    private LocalDateTime rankFinalizedAt;

    @TableField(exist = false)
    private Boolean registered = false;
}
