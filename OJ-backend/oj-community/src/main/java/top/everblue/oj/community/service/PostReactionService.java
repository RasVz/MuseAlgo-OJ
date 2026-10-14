package top.everblue.oj.community.service;

import java.util.Map;

/**
 * 动态表情回应服务。
 * <p>
 * 方法命名上「toggle + active 布尔」的组合看着别扭，实则是刻意的：
 * {@code active=true} 表示「设为点赞」、{@code false} 表示「取消点赞」，
 * 即它是带明确目标态的幂等写，而非「翻转」语义。
 * 名字沿用 toggle 是为了与前端接口保持一致，不要按字面理解成取反。
 * </p>
 * <p>
 * 表情类型由 {@code post_reaction.reaction_type} 原样存字符串，本层不做枚举校验。
 * </p>
 */
public interface PostReactionService {
    Map<String, Object> toggleLike(Long userId, Long postId, Boolean active);

    Map<String, Object> toggleDislike(Long userId, Long postId, Boolean active);

    String getUserReaction(Long userId, Long postId);

    long countLikes(Long postId);

    long countDislikes(Long postId);
}
