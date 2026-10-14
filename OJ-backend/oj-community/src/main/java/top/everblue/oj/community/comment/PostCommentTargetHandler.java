package top.everblue.oj.community.comment;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.everblue.oj.community.entity.Comment;
import top.everblue.oj.community.entity.Post;
import top.everblue.oj.community.service.PostHotService;
import top.everblue.oj.community.service.PostService;
import top.everblue.oj.community.post.PostTargetService;
import top.everblue.oj.community.notification.NotificationTargetType;

import java.util.Map;
import java.util.Set;

/** Owns validation and counter updates for post and solution discussions. */
/**
 * 动态与题解的评论目标处理器。
 * <p>
 * 同时承接 {@code entityType} 1（评论动态）与 3（评论题解）——
 * 两者落在同一张 {@code post} 表、计数与排行逻辑完全一致，只是允许的类型不同，
 * 因此没有拆成两个处理器。具体哪个类型配哪类动态，由
 * {@link top.everblue.oj.community.post.PostTargetService} 统一校验。
 * </p>
 * <p>
 * 计数要写两处（{@code post.comment_count} 与 Redis），且 Redis 侧必须先补种：
 * 否则首次评论时 Redis 里没有该帖的统计字段，排行会从 1 开始而不是 N+1。
 * </p>
 */
@Service
@RequiredArgsConstructor
class PostCommentTargetHandler implements CommentTargetHandler {

    private final PostService postService;
    private final PostHotService postHotService;
    private final PostTargetService postTargets;

    @Override
    public Set<Integer> supportedTypes() {
        return Set.of(1, 3);
    }

    /**
     * 校验评论目标并取出通知所需的元数据。
     * <p>
     * {@code supportedTypes} 里的 1 与 3 都要在这里被
     * {@link PostTargetService#requireCommentTarget} 校验匹配关系，
     * 动态与题解配错类型会在此处被拒。
     * </p>
     *
     * @throws IllegalArgumentException 目标不存在、已删除，或评论类型与动态类型不匹配
     */
    @Override
    public CommentTargetDetails requireTarget(Long targetId, Integer targetType) {
        Post post = postTargets.requireCommentTarget(targetId, targetType);
        return new CommentTargetDetails(NotificationTargetType.POST, post.getAuthorId());
    }

    /**
     * 评论落库后回写计数并刷新热度排行。
     * <p>
     * 顺序不能调换：必须先 {@code initializeStatsIfAbsent} 再自增，
     * 否则 Redis 中该帖的 {@code comments} 字段缺失时，自增会从 0 起算，
     * 排行的评论权重会被严重低估。
     * </p>
     * <p>
     * 此处在评论发布事务内调用，因此这里的 {@link IllegalStateException}
     * 会连带回滚评论本身——宁可让用户重发，也不能留下「评论数与实际不符」的状态。
     * </p>
     *
     * @throws IllegalStateException 目标在落库后已消失（并发删除）
     */
    @Override
    public void afterCommentPublished(Comment comment, CommentTargetDetails target) {
        Post post = postService.getById(comment.getEntityId());
        if (post == null) throw new IllegalStateException("评论目标不存在");
        postHotService.initializeStatsIfAbsent(post.getId(), Map.of(
                "views", value(post.getViews()), "likes", value(post.getLikes()), "dislikes", value(post.getDislikes()),
                "favorites", value(post.getFavorites()), "comments", value(post.getCommentCount())));
        postService.update(new LambdaUpdateWrapper<Post>()
                .setSql("comment_count = comment_count + 1")
                .eq(Post::getId, comment.getEntityId()));
        postHotService.recordComment(post.getId(), hotCategory(post), isTop(post), isEssence(post), post.getCreateTime());
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private String hotCategory(Post post) {
        return Integer.valueOf(1).equals(post.getType()) ? "solution" : post.getCategory();
    }

    private boolean isTop(Post post) {
        return Integer.valueOf(1).equals(post.getIsTop());
    }

    private boolean isEssence(Post post) {
        return Integer.valueOf(1).equals(post.getIsEssence());
    }
}
