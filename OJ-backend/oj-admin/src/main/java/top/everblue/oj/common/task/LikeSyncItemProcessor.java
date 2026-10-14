package top.everblue.oj.common.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import top.everblue.oj.community.entity.Comment;
import top.everblue.oj.community.entity.CommentLike;
import top.everblue.oj.community.mapper.CommentLikeMapper;
import top.everblue.oj.community.mapper.CommentMapper;
import top.everblue.oj.community.service.LikeService;

import java.util.Set;

/**
 * 单条评论点赞落库处理器。
 * <p>
 * 从 {@link SyncLikeTask} 抽出的独立 Bean：每条评论一个独立事务，
 * 单条失败只回滚自己，不影响同批其他评论（避免大事务整批回滚
 * 导致已成功条目与 Redis dirty 标记不一致的数据丢失）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikeSyncItemProcessor {

    private final LikeService likeService;

    private final CommentMapper commentMapper;

    private final CommentLikeMapper commentLikeMapper;

    /**
     * 同步单条评论的点赞：更新 comment.like_count + 重建 comment_like 关系。
     *
     * @param commentId 评论 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void syncOne(Long commentId) {
        // 1. 同步 LikeCount 到 comment 表
        Integer redisCount = likeService.getLikeCount(commentId);
        Comment comment = new Comment();
        comment.setId(commentId);
        comment.setLikeCount(redisCount);
        commentMapper.updateById(comment);

        // 2. 同步具体点赞关系到 comment_like 表
        Set<Object> userIds = likeService.getLikedUserIds(commentId);
        commentLikeMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CommentLike>()
                .eq(CommentLike::getCommentId, commentId));

        if (userIds != null && !userIds.isEmpty()) {
            for (Object uidObj : userIds) {
                Long userId = toLong(uidObj);
                if (userId == null) {
                    log.warn("忽略无法识别的评论点赞用户: commentId={}, user={}", commentId, uidObj);
                    continue;
                }

                CommentLike cl = new CommentLike();
                cl.setCommentId(commentId);
                cl.setUserId(userId);
                commentLikeMapper.insert(cl);
            }
        }
    }

    static Long toLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? null : Long.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
