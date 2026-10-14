package top.everblue.oj.community.entity.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 发表评论的请求体。
 * <p>
 * {@code entityType} 与 {@code parentId} 的语义由前端保证，这里只做非空/非空串校验：
 * 越界取值与「回复了一个不存在的父评论」属于业务错误，交给
 * {@link top.everblue.oj.community.service.CommentService} 判定。
 * </p>
 */
@Data
public class CommentDTO {
    @NotNull(message = "必须指定评论对象")
    private Long entityId;

    // 1:动态 2:题目 3:题解
    private Integer entityType = 1;

    @NotBlank(message = "评论内容不能为空")
    private String content;

    //如果是回复某人，前端传这个；如果是根评论，前端传0或不传
    private Long parentId;
    private Long replyUserId;
    private Long replyCommentId;
}
