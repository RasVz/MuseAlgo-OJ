package top.everblue.oj.community.service;

import java.util.Set;

/**
 * 评论点赞服务。
 * <p>
 * 接口同时保留「显式幂等」与「隐式切换」两套方法，是为兼容两条调用链：
 * 新的社区互动模块走 {@link #activate} / {@link #deactivate}（重复请求结果一致），
 * 旧的 Controller 走 {@link #toggleLike}（每次调用都翻转）。
 * 新代码一律用前者——切换语义在并发下不成立，原因见
 * {@link top.everblue.oj.community.service.impl.LikeServiceImpl#toggleLike}。
 * </p>
 * <p>
 * 实现见 {@link top.everblue.oj.community.service.impl.LikeServiceImpl}，
 * 数据只存 Redis，尚未落库。
 * </p>
 */
public interface LikeService {
    /** Explicit, idempotent actions used by the community interaction module. */
    boolean activate(Long commentId, Long userId);

    boolean deactivate(Long commentId, Long userId);

    boolean isLiked(Long commentId, Long userId);

    /** Legacy context-bound toggle retained only for older callers. */
    void toggleLike(Long commentId);

    Integer getLikeCount(Long commentId);

    Boolean isLiked(Long commentId);

    Set<Object> getLikedUserIds(Long commentId);
}
