package top.everblue.oj.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 评论点赞记录。
 * <p>
 * 与 {@link top.everblue.oj.community.entity.UserLike}（动态点赞/踩）分表存放：
 * 两者字段几乎相同，但读写路径、唯一约束与统计口径都不同，合表会让评论区的
 * 点赞热更新反复争抢同一行。
 * </p>
 * <p>
 * 字段名未显式标注 {@code @TableField}，依赖 MyBatis-Plus 的驼峰转下划线映射。
 * </p>
 */
@Data
@TableName("comment_like") // 对应数据库表名
public class CommentLike {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;      // 对应 user_id
    private Long commentId;   // 对应 comment_id

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}