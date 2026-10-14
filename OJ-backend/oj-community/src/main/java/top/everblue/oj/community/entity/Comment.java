package top.everblue.oj.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;

/**
 * 评论表
 * @TableName comment
 */
@TableName(value ="comment")
@Data
public class Comment {
    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 评论者ID (谁发的)
     */
    @TableField(value = "user_id")
    private Long userId;

    /**
     * 被评论对象的ID (例如动态ID、题目ID)
     */
    @TableField(value = "entity_id")
    private Long entityId;

    /**
     * 评论类型 (1:动态 2:题目 3:题解) - 方便以后复用
     */
    @TableField(value = "entity_type")
    private Integer entityType;

    /**
     * 评论内容
     */
    @TableField(value = "content")
    private String content;

    /**
     * 父评论ID (0表示这是一条根评论)
     */
    @TableField(value = "parent_id")
    private Long parentId;

    /**
     * 被回复者ID (如果是在回复某人)
     */
    @TableField(value = "reply_user_id")
    private Long replyUserId;

    /**
     * 创建时间
     */
    @TableField(value = "create_time")
    private Date createTime;

    /**
     * 逻辑删除
     */
    @TableField(value = "is_deleted")
    private Integer isDeleted;

    @TableField(value = "like_count")
    private Integer likeCount;

    @TableField(value = "reply_count")
    private Integer replyCount;

    @TableField(value = "update_time")
    private Date updateTime;
}