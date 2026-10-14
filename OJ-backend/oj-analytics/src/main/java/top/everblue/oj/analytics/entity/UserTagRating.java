package top.everblue.oj.analytics.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户-标签 ELO 评分表。
 * <p>
 * 学生每 AC 一道题，根据题目的 tag 计算 ELO 增益并 upsert。
 * 评分只增不减，上限为题难度 + 200。
 */
@Getter
@Setter
@TableName("user_tag_rating")
public class UserTagRating implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableField("user_id")
    private Long userId;

    @TableField("tag_id")
    private Long tagId;

    @TableField("rating")
    private Integer rating;

    @TableField("problem_count")
    private Integer problemCount;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
