package top.everblue.oj.community.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.community.service.LikeService;

import java.time.Duration;
import java.util.Set;

/**
 * 评论点赞：Redis 集合作为唯一事实来源，交互实时生效。
 * <p>
 * <b>为什么点赞不直接写库：</b>
 * 点赞是高频写路径（点一下即一次），且最终一致即可。走 Redis Set 可用
 * {@code SADD}/{@code SREM} 的原子返回直接判定「状态是否真的翻转」，
 * 而「先查再插」的数据库写法在并发下需要唯一索引兜底并处理冲突重试，
 * 代码量与出错面都更大。
 * </p>
 * <p>
 * <b>注意——当前的持久化链路并未接通：</b>
 * 本类只写 Redis，{@link #markDirty} 把变更过的评论 ID 塞进
 * {@code comment:like:dirty_ids}，并预留了 {@link #getLikedUserIds} 供落库读取。
 * 但全仓<b>没有任何定时任务消费该 dirty 集合</b>，
 * {@code comment_like} 表因此始终为空。所以点赞数据只存在于 Redis，
 * TTL 到期即丢失（已过期 key 的评论点赞数会归零）。
 * 新增落库消费任务时，请复用 {@link #getLikedUserIds}，不要绕过它直接读 Redis key。
 * </p>
 * <p>
 * <b>为何不干脆去掉 dirty 机制：</b>
 * 它标记的是「哪些 key 需要回写」的边界，加载定时任务时无需全量扫描评论表，
 * 本身是正确设计；缺的只是消费者。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class LikeServiceImpl implements LikeService {

    private final RedisTemplate<String, Object> redisTemplate;

    // Redis Key 前缀
    private static final String LIKE_USERS_KEY_PREFIX = "comment:like:users:";
    private static final String DIRTY_COMMENTS_KEY = "comment:like:dirty_ids";

    /**
     * 点赞集合 TTL（默认 30 天）。
     * <p>
     * 防「永不过期」内存膨胀：落库后 Redis 集合仍保留用于实时计数/状态查询，
     * 若评论长期无人点赞则自然过期回收。活跃评论每次新点赞都会续期。
     * 副作用权衡：沉寂超过 TTL 的评论 Redis 计数归零（DB 中已同步的计数不受影响），
     * 对已无人关注的评论影响可忽略。
     * </p>
     */
    @Value("${comment.like.ttl-days:30}")
    private long likeSetTtlDays;

    /**
     * 点赞 / 取消点赞 (切换状态)
     * <p>
     * 仅供旧调用方使用；新链路走 {@link #activate} / {@link #deactivate} 这对显式幂等接口。
     * 本方法是「读当前态再翻转」，并发下两个请求可能算出同样的目标态而互相抵消；
     * 显式接口由 {@code SADD} 的返回值天然去重，不需要这层读。
     * </p>
     *
     * @throws RuntimeException 未登录时抛出
     */
    @Override
    public void toggleLike(Long commentId) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) throw new RuntimeException("请先登录");
        if (isLiked(commentId, userId)) deactivate(commentId, userId);
        else activate(commentId, userId);
    }

    @Override
    public boolean activate(Long commentId, Long userId) {
        if (commentId == null || userId == null) return false;
        String key = LIKE_USERS_KEY_PREFIX + commentId;
        Long added = redisTemplate.opsForSet().add(key, userId);
        boolean changed = added != null && added > 0;
        if (changed) {
            // 续期 TTL：活跃评论的集合不回收，防永不过期膨胀
            redisTemplate.expire(key, Duration.ofDays(likeSetTtlDays));
            markDirty(commentId);
        }
        return changed;
    }

    @Override
    public boolean deactivate(Long commentId, Long userId) {
        if (commentId == null || userId == null) return false;
        Long removed = redisTemplate.opsForSet().remove(LIKE_USERS_KEY_PREFIX + commentId, userId);
        boolean changed = removed != null && removed > 0;
        if (changed) markDirty(commentId);
        return changed;
    }

    /**
     * 标记该评论的点赞数据待落库。
     * <p>
     * 用 Set 而非 List：重复标记同一评论会被 Set 自动去重，
     * 避免热门评论在一次刷库周期内把 dirty 集合撑大。
     * </p>
     */
    private void markDirty(Long commentId) {
        redisTemplate.opsForSet().add(DIRTY_COMMENTS_KEY, commentId);
        // dirty 标记集合同样设 TTL，防空集合残留；30 秒消费一次的节奏下丢失风险可忽略
        redisTemplate.expire(DIRTY_COMMENTS_KEY, Duration.ofDays(likeSetTtlDays));
    }

    /**
     * 获取某评论的点赞数
     */
    @Override
    public Integer getLikeCount(Long commentId) {
        String key = LIKE_USERS_KEY_PREFIX + commentId;
        // SCARD 获取集合大小，O(1) 复杂度
        Long size = redisTemplate.opsForSet().size(key);
        return size != null ? size.intValue() : 0;
    }

    /**
     * 判断当前用户是否点赞
     */
    @Override
    public Boolean isLiked(Long commentId) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return false;
        return isLiked(commentId, userId);
    }

    @Override
    public boolean isLiked(Long commentId, Long userId) {
        if (commentId == null || userId == null) return false;
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(LIKE_USERS_KEY_PREFIX + commentId, userId));
    }

    /**
     * 批量查询某评论的所有点赞用户ID (用于持久化)
     */
    @Override
    public Set<Object> getLikedUserIds(Long commentId) {
        return redisTemplate.opsForSet().members(LIKE_USERS_KEY_PREFIX + commentId);
    }
}
