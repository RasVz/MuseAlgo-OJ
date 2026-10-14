package top.everblue.oj.community.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.everblue.oj.community.mapper.PostMapper;
import top.everblue.oj.community.mapper.PostReactionMapper;
import top.everblue.oj.community.service.PostReactionService;

import java.util.HashMap;
import java.util.Map;

/**
 * 动态表情回应的写入与计数回写。
 * <p>
 * 「先查当前态再决定插还是改」的两段式实现看似可以优化成一条 upsert，
 * 但本表有 {@code (post_id, user_id)} 唯一键，且需要区分「无记录 → 插入」
 * 与「有记录 → 改值」两种分支来填充 {@code create_time}（插入才写，改值不写）。
 * 用 {@code ON DUPLICATE KEY UPDATE} 也能做到，但会把「不改值」的情形也走一遍 UPDATE，
 * 白白刷新 {@code update_time}，让「用户最近一次改变态度的时间」失真。
 * </p>
 * <p>
 * 已知取舍：查与写之间有并发窗口——同一用户两个并发请求可能都读到「无记录」
 * 而双双插入，后者触发唯一键冲突导致整个事务回滚（{@code rollbackFor} 兜住了异常）。
 * 表上唯一键保证了不会产生重复行，代价是极端并发下需前端重试；
 * 因点赞交互无实时一致性要求，保持现状。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class PostReactionServiceImpl implements PostReactionService {

    private static final String LIKE = "like";
    private static final String DISLIKE = "dislike";

    private final PostReactionMapper postReactionMapper;

    private final PostMapper postMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> toggleLike(Long userId, Long postId, Boolean active) {
        String currentReaction = postReactionMapper.selectUserReaction(postId, userId);

        if (Boolean.TRUE.equals(active)) {
            saveOrUpdateReaction(postId, userId, LIKE, currentReaction);
        } else if (LIKE.equals(currentReaction)) {
            postReactionMapper.deleteReaction(postId, userId);
        }

        return buildReactionState(postId, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> toggleDislike(Long userId, Long postId, Boolean active) {
        String currentReaction = postReactionMapper.selectUserReaction(postId, userId);

        if (Boolean.TRUE.equals(active)) {
            saveOrUpdateReaction(postId, userId, DISLIKE, currentReaction);
        } else if (DISLIKE.equals(currentReaction)) {
            postReactionMapper.deleteReaction(postId, userId);
        }

        return buildReactionState(postId, userId);
    }

    @Override
    public String getUserReaction(Long userId, Long postId) {
        return postReactionMapper.selectUserReaction(postId, userId);
    }

    @Override
    public long countLikes(Long postId) {
        return postReactionMapper.countByPostIdAndReaction(postId, LIKE);
    }

    @Override
    public long countDislikes(Long postId) {
        return postReactionMapper.countByPostIdAndReaction(postId, DISLIKE);
    }

    /**
     * 插入或改值；目标与当前一致时不发 SQL，避免无意义写。
     *
     * @param currentReaction 已有的回应类型；{@code null} 或空白串视为无记录
     */
    private void saveOrUpdateReaction(Long postId, Long userId, String targetReaction, String currentReaction) {
        if (currentReaction == null || currentReaction.trim().isEmpty()) {
            postReactionMapper.insertReaction(postId, userId, targetReaction);
            return;
        }

        if (!targetReaction.equals(currentReaction)) {
            postReactionMapper.updateReaction(postId, userId, targetReaction);
        }
    }

    /**
     * 回写动态主表计数并组装前端需要的最新状态。
     * <p>
     * <b>注意 {@code post} 表同时存在 {@code likes} 与 {@code dislikes} 两列，但这里只回写点赞数：</b>
     * 也就是说 {@code post.dislikes} 会长期停留在 0，与 {@link #countDislikes} 的实时结果不一致
     * （{@link PostMapper} 目前也只有 {@code updateLikeCount}，没有对应的踩数更新方法）。
     * 如果列表页或详情页开始展示点踩数、或后台导出用到该列，
     * 必须在此处一并回写，否则会看到「点赞 100、踩 0」这种不可能的组合。
     * 当前点踩数只在前端拿到的响应体里实时给出，故暂未接上主表。
     * </p>
     * <p>
     * 计数来自 {@code COUNT(*)} 实时查而非增量加减：一次互动只影响一行，
     * 实时查的代价可接受，换来计数永远不会漂移。
     * </p>
     */
    private Map<String, Object> buildReactionState(Long postId, Long userId) {
        long likeCount = countLikes(postId);
        long dislikeCount = countDislikes(postId);
        String currentReaction = getUserReaction(userId, postId);

        postMapper.updateLikeCount(postId, likeCount);

        Map<String, Object> result = new HashMap<>();
        result.put("likeCount", likeCount);
        result.put("dislikeCount", dislikeCount);
        result.put("isLiked", LIKE.equals(currentReaction));
        result.put("isDisliked", DISLIKE.equals(currentReaction));
        return result;
    }
}
