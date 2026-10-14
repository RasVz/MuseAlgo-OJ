package top.everblue.oj.community.entity.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 评论在列表页的出参。
 * <p>
 * 已在 Service 层组装好「发评论人」与「被回复人」两份用户摘要，
 * 前端不再二次请求用户接口；同时带上 {@code isLiked} 让前端免去点赞态查询。
 * </p>
 * <p>
 * {@code children} 目前<b>没有任何服务端写入方</b>（全仓无 {@code setChildren} 调用），
 * 属于预留字段：接口返回的评论是平铺列表，楼中楼由前端按 {@code parentId} 自行分组。
 * 若将来改为服务端组装嵌套树，注意只能嵌套一层，
 * 再深就会出现无限深的树形结构与体积不可控的响应。
 * </p>
 */
@Data
public class CommentVO {
    private Long id;
    private String content;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm", timezone = "GMT+8")
    private LocalDateTime createTime;

    private Long parentId;

    // === 发评论的人 ===
    private Long userId;
    private String nickname;
    private String avatar;

    // === 被回复的人（楼中楼用） ===
    private Long replyUserId;
    private String replyNickname;

    private Integer likeCount;
    private Integer replyCount;

    private Boolean isLiked;

    // === 子评论列表 ===
    private List<CommentVO> children;
}