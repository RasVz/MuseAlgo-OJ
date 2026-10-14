package top.everblue.oj.community.interaction;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.everblue.oj.community.entity.Post;
import top.everblue.oj.community.entity.UserFavorite;
import top.everblue.oj.community.service.NotificationService;
import top.everblue.oj.community.service.PostHotService;
import top.everblue.oj.community.service.PostService;
import top.everblue.oj.community.service.UserFavoriteService;
import top.everblue.oj.community.post.PostTargetService;
import top.everblue.oj.community.notification.NotificationCommand;
import top.everblue.oj.community.notification.NotificationTargetType;
import top.everblue.oj.community.notification.NotificationType;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Owns all post reaction state, counters, ranking refreshes, and notifications. */
/**
 * 动态点赞、点踩、收藏的互动处理器，是本模块副作用最重的一个实现。
 * <p>
 * <b>事务只覆盖数据库部分：</b>互动记录与 {@code post} 表计数在
 * {@link Transactional} 边界内，要么全成要么全回滚；
 * Redis 侧的计数与排行<b>不受事务管辖</b>，也因此不会随数据库回滚而撤销。
 * 实现上把 {@code requirePost} 校验放在所有写操作之前，
 * 保证「校验失败」这条路径不会留下 Redis 脏状态——
 * 这是 Redis 无事务参与时唯一可靠的规避手段。
 * </p>
 * <p>
 * <b>三类的状态来源不同，计数策略也不同：</b>
 * 点赞、点踩存在 Redis 集合里，计数由 {@link PostHotService} 的 Lua 脚本原子维护，
 * 不额外回写主表；收藏存在 {@code user_favorite} 表（软取消状态机），
 * 需要用 {@code incrementFavoriteCount} 增量回写 {@code post.favorites}。
 * 因此三种互动不能共用一套计数逻辑，这也是它们各自有独立私有方法的原因。
 * </p>
 * <p>
 * 类为包级可见：只经 {@link CommunityInteractionService} 分派使用。
 * </p>
 */
@Service
@RequiredArgsConstructor
class PostInteractionHandler implements CommunityInteractionHandler {

    private static final int POST_FAVORITE_TARGET_TYPE = 4;

    private final PostService postService;
    private final UserFavoriteService userFavoriteService;
    private final PostHotService postHotService;
    private final NotificationService notificationService;
    private final PostTargetService postTargets;

    @Override
    public InteractionTargetType targetType() {
        return InteractionTargetType.POST;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InteractionResult activate(InteractionCommand command) {
        Post post = requirePost(command.targetId());
        initializeStats(post);
        boolean changed = switch (command.action()) {
            case LIKE -> activateLike(command, post);
            case DISLIKE -> postHotService.dislike(command.targetId(), command.actorId(), hotCategory(post), isTop(post), isEssence(post), post.getCreateTime());
            case FAVORITE -> activateFavorite(command, post);
        };
        return result(command.targetId(), changed, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InteractionResult deactivate(InteractionCommand command) {
        Post post = requirePost(command.targetId());
        initializeStats(post);
        boolean changed = switch (command.action()) {
            case LIKE -> postHotService.unlike(command.targetId(), command.actorId(), hotCategory(post), isTop(post), isEssence(post), post.getCreateTime());
            case DISLIKE -> postHotService.undislike(command.targetId(), command.actorId(), hotCategory(post), isTop(post), isEssence(post), post.getCreateTime());
            case FAVORITE -> deactivateFavorite(command, post);
        };
        return result(command.targetId(), changed, false);
    }

    @Override
    public boolean isActive(InteractionCommand command) {
        requirePost(command.targetId());
        return switch (command.action()) {
            case LIKE -> postHotService.hasLiked(command.targetId(), command.actorId());
            case DISLIKE -> postHotService.hasDisliked(command.targetId(), command.actorId());
            case FAVORITE -> isFavorited(command.actorId(), command.targetId());
        };
    }

    private boolean activateLike(InteractionCommand command, Post post) {
        boolean changed = postHotService.like(command.targetId(), command.actorId(), hotCategory(post), isTop(post), isEssence(post), post.getCreateTime());
        if (changed) {
            notificationService.notify(new NotificationCommand(post.getAuthorId(), command.actorId(), NotificationType.LIKE,
                    NotificationTargetType.POST, post.getId(), "有人点赞了你的内容", post.getTitle()));
        }
        return changed;
    }

    private boolean activateFavorite(InteractionCommand command, Post post) {
        boolean changed = activateFavoriteState(command.actorId(), command.targetId());
        if (changed) {
            incrementFavoriteCount(post.getId(), 1);
            postHotService.favorite(command.targetId(), command.actorId(), hotCategory(post), isTop(post), isEssence(post), post.getCreateTime());
            notificationService.notify(new NotificationCommand(post.getAuthorId(), command.actorId(), NotificationType.FAVORITE,
                    NotificationTargetType.POST, post.getId(), "有人收藏了你的内容", post.getTitle()));
        }
        return changed;
    }

    private boolean deactivateFavorite(InteractionCommand command, Post post) {
        boolean changed = deactivateFavoriteState(command.actorId(), command.targetId());
        if (changed) {
            incrementFavoriteCount(post.getId(), -1);
            postHotService.unfavorite(command.targetId(), command.actorId(), hotCategory(post), isTop(post), isEssence(post), post.getCreateTime());
        }
        return changed;
    }

    private InteractionResult result(Long postId, boolean changed, boolean active) {
        Map<String, Integer> raw = postHotService.getPostStats(postId);
        Map<String, Integer> statistics = new LinkedHashMap<>();
        statistics.put("likes", raw.getOrDefault("likes", 0));
        statistics.put("dislikes", raw.getOrDefault("dislikes", 0));
        statistics.put("favorites", raw.getOrDefault("favorites", 0));
        statistics.put("comments", raw.getOrDefault("comments", 0));
        statistics.put("views", raw.getOrDefault("views", 0));
        return new InteractionResult(changed, active, statistics);
    }

    private boolean activateFavoriteState(Long userId, Long postId) {
        LocalDateTime now = LocalDateTime.now();
        boolean restored = userFavoriteService.update(null, new LambdaUpdateWrapper<UserFavorite>()
                .set(UserFavorite::getStatus, 1).set(UserFavorite::getCancelTime, null).set(UserFavorite::getUpdateTime, now)
                .eq(UserFavorite::getUserId, userId).eq(UserFavorite::getTargetId, postId)
                .eq(UserFavorite::getTargetType, POST_FAVORITE_TARGET_TYPE).eq(UserFavorite::getStatus, 0));
        if (restored || isFavorited(userId, postId)) return restored;

        UserFavorite favorite = new UserFavorite();
        favorite.setUserId(userId);
        favorite.setTargetId(postId);
        favorite.setTargetType(POST_FAVORITE_TARGET_TYPE);
        favorite.setStatus(1);
        favorite.setCreateTime(now);
        favorite.setUpdateTime(now);
        try {
            return userFavoriteService.save(favorite);
        } catch (DuplicateKeyException ignored) {
            return userFavoriteService.update(null, new LambdaUpdateWrapper<UserFavorite>()
                    .set(UserFavorite::getStatus, 1).set(UserFavorite::getCancelTime, null).set(UserFavorite::getUpdateTime, now)
                    .eq(UserFavorite::getUserId, userId).eq(UserFavorite::getTargetId, postId)
                    .eq(UserFavorite::getTargetType, POST_FAVORITE_TARGET_TYPE).eq(UserFavorite::getStatus, 0));
        }
    }

    private boolean deactivateFavoriteState(Long userId, Long postId) {
        LocalDateTime now = LocalDateTime.now();
        return userFavoriteService.update(null, new LambdaUpdateWrapper<UserFavorite>()
                .set(UserFavorite::getStatus, 0).set(UserFavorite::getCancelTime, now).set(UserFavorite::getUpdateTime, now)
                .eq(UserFavorite::getUserId, userId).eq(UserFavorite::getTargetId, postId)
                .eq(UserFavorite::getTargetType, POST_FAVORITE_TARGET_TYPE).eq(UserFavorite::getStatus, 1));
    }

    private boolean isFavorited(Long userId, Long postId) {
        return userFavoriteService.count(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId).eq(UserFavorite::getTargetId, postId)
                .eq(UserFavorite::getTargetType, POST_FAVORITE_TARGET_TYPE).eq(UserFavorite::getStatus, 1)) > 0;
    }

    /**
     * 收藏计数回写主表。
     * <p>
     * <b>减法用 {@code CASE WHEN} 兜底到 0，而不是 {@code favorites - 1}：</b>
     * 若历史数据存在计数小于实际收藏数的行（早期逻辑漂移），
     * 直接减会减成负数，而负的收藏数会让列表页的排序与展示都失真。
     * 用 SQL 表达式而非「先查后写」是为了避免读改写之间的并发覆盖。
     * </p>
     *
     * @param delta 仅取 {@code +1} 或 {@code -1}
     */
    private void incrementFavoriteCount(Long postId, int delta) {
        String expression = delta > 0 ? "favorites = favorites + 1"
                : "favorites = CASE WHEN favorites > 0 THEN favorites - 1 ELSE 0 END";
        postService.update(new LambdaUpdateWrapper<Post>().setSql(expression).eq(Post::getId, postId));
    }

    /**
     * 把主表计数补种进 Redis（仅在 key 缺失时写入）。
     * <p>
     * 必须在每次互动前调用：{@link PostHotService} 的 Lua 脚本按字段增减，
     * 起点为 0，若 Redis 尚未建好该帖的统计 hash，首次互动会得到 1 而不是 N。
     * 这里走的是 {@code initializeStatsIfAbsent}，不会覆盖 Redis 中已有的真实计数。
     * </p>
     */
    private void initializeStats(Post post) {
        postHotService.initializeStatsIfAbsent(post.getId(), Map.of(
                "views", value(post.getViews()), "likes", value(post.getLikes()), "dislikes", value(post.getDislikes()),
                "favorites", value(post.getFavorites()), "comments", value(post.getCommentCount())));
    }

    private Post requirePost(Long postId) {
        return postTargets.requireExisting(postId);
    }

    private int value(Integer value) { return value == null ? 0 : value; }

    private String hotCategory(Post post) { return Integer.valueOf(1).equals(post.getType()) ? "solution" : post.getCategory(); }

    private boolean isTop(Post post) { return Integer.valueOf(1).equals(post.getIsTop()); }

    private boolean isEssence(Post post) { return Integer.valueOf(1).equals(post.getIsEssence()); }
}
