package top.everblue.oj.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Hint Ladder 解锁轨迹（ai_hint_trace 表，V44 迁移）。
 * <p>
 * 学生每次成功解锁下一级引导都落一条轨迹（只升不降语义下记录的是"本次解锁到的级别"），
 * 是解锁状态的权威历史：教师按班级查看、掌握度算法消费都走这张表；
 * Redis 只是当前级别的短期缓存。
 * </p>
 */
@Getter
@Setter
@TableName("ai_hint_trace")
public class AiHintTrace implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 解锁来源：学生主动使用阶梯解锁 */
    public static final String SOURCE_USER = "USER";
    /** 解锁来源：教师/管理员代解锁 */
    public static final String SOURCE_TEACHER = "TEACHER";
    /** 解锁来源：管理员代解锁 */
    public static final String SOURCE_ADMIN = "ADMIN";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("problem_id")
    private Long problemId;

    /** 本次解锁到的级别 1-4 */
    @TableField("level")
    private Integer level;

    /** USER / TEACHER / ADMIN */
    @TableField("source")
    private String source;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
