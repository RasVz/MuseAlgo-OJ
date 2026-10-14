package top.everblue.oj.community.interaction;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.everblue.oj.community.entity.vo.PostStatsVO;
import top.everblue.oj.community.service.PostHotService;
import top.everblue.oj.community.post.PostTargetService;

import java.util.Map;

/** Keeps post reaction command dispatch and client snapshots consistent across HTTP adapters. */
/**
 * 动态互动门面的实现：分派动作后立即回读统计快照。
 * <p>
 * 「点赞 / 收藏」两个字段的来源刻意不同：
 * 点赞态读 {@link PostHotService} 的 Redis 集合，
 * 收藏态走 {@link CommunityInteractionService} 查数据库软取消状态机。
 * 因为两者的存储介质本就不一致（Redis 集合 vs status 字段），
 * 强行统一到一个存储里反而要改动已有数据结构。
 * 代价是两者的实时性略有差异——Redis 过期时点赞态会短暂回落到主表计数。
 * </p>
 * <p>
 * 类为包级可见：只经接口注入使用。
 * </p>
 */
@Service
@RequiredArgsConstructor
class PostInteractionFacadeImpl implements PostInteractionFacade {

    private final CommunityInteractionService interactions;
    private final PostHotService postHotService;
    private final PostTargetService postTargets;

    /**
     * 执行一次互动并返回最新统计。
     * <p>
     * 无论状态是否真的翻转（重复点赞同一动态）都返回快照，
     * 响应中的 {@code changed} 字段告知前端「这次没生效」，
     * 让前端能把按钮回滚到正确状态而不是乐观地假装成功。
     * </p>
     *
     * @throws IllegalArgumentException 动态不存在或已删除
     */
    @Override
    public PostStatsVO apply(Long userId, Long postId, InteractionAction action, boolean active) {
        InteractionCommand command = new InteractionCommand(userId, InteractionTargetType.POST, postId, action);
        if (active) interactions.activate(command);
        else interactions.deactivate(command);
        return snapshot(userId, postId);
    }

    @Override
    public PostStatsVO snapshot(Long userId, Long postId) {
        postTargets.requireExisting(postId);
        Map<String, Integer> values = postHotService.getPostStats(postId);
        PostStatsVO stats = new PostStatsVO();
        stats.setLikes(values.getOrDefault("likes", 0));
        stats.setFavorites(values.getOrDefault("favorites", 0));
        stats.setComments(values.getOrDefault("comments", 0));
        stats.setViews(values.getOrDefault("views", 0));
        stats.setUserLiked(userId != null && postHotService.hasLiked(postId, userId));
        stats.setUserFaved(userId != null && interactions.isActive(
                new InteractionCommand(userId, InteractionTargetType.POST, postId, InteractionAction.FAVORITE)));
        return stats;
    }
}
