package top.everblue.oj.analytics.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 题目-标签关联表。
 * <p>
 * 联合主键 (problem_id, tag_id)，用于替代 {@code problem.tags} JSON 字段
 * 在画像统计中的查询需求。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName("problem_tag")
public class ProblemTag implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableField("problem_id")
    private Long problemId;

    @TableField("tag_id")
    private Long tagId;

    @TableField("create_time")
    private LocalDateTime createTime;
}