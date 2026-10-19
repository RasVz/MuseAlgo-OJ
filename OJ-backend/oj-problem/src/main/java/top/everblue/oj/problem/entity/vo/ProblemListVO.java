package top.everblue.oj.problem.entity.vo;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 题单列表项的出参。
 * <p>
 * 承担两类字段：题单自身属性，以及为省掉前端二次请求而由关联查询带出的
 * 统计值（题目数、已解题数、班级名、课件引用数）。带出的统计值随查询范围变化，
 * <b>不代表题单实体上的持久化字段</b>，保存时不要回传它们。
 * </p>
 */
@Data
public class ProblemListVO {
    private String creatorName;
    private Long creatorId;
    private String creatorAvatar;

    private Long id;
    private String title;
    private Byte type;

    private String description;
    private Integer privacy; // 0-私有, 1-公开
    private Integer favoriteCount;

    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime updateTime;

    // 下面这两个是 SQL 关联查询出来的额外字段
    private String className;
    private Long classId;
    private Integer problemCount;
    private Integer solvedCount;

    // 被几个课件（Slidev PPT）使用
    private Integer coursewareCount;
}