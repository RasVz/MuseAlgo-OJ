package top.everblue.oj.contest.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 比赛报名记录，对应 contest_user 表。
 * <p>
 * 表示「某用户报名了某场比赛」这一关系本身，不含任何成绩字段——成绩在
 * {@link ContestRank}。本表唯一的作用是<b>准入判定</b>：只有在本表存在记录的
 * 用户才能对这场比赛的题目提交，避免未报名者混入榜单。
 * </p>
 * <p>
 * 一行 = 一次报名，重复报名由数据库唯一约束拦下，服务层无需额外去重。
 * </p>
 */
@Data
@TableName("contest_user")
public class ContestUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long contestId;
    private Long userId;
    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}