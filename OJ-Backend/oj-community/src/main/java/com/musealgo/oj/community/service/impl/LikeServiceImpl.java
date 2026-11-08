package com.musealgo.oj.community.service.impl;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.common.exception.CommonErrorCode;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.community.service.LikeService;

import java.time.Duration;
import java.util.Set;

/** 评论点赞缓存；冷缓存先恢复持久化关系，dirty 集合由定时任务回写。 */
@Service
@RequiredArgsConstructor
public class LikeServiceImpl implements LikeService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final com.musealgo.oj.community.mapper.CommentLikeMapper commentLikeMapper;
    private static final String EMPTY = "__initialized_empty__";
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> INSTALL =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('exists',KEYS[1])==0 then redis.call('rename',KEYS[2],KEYS[1]); return 1 "
                            + "else redis.call('del',KEYS[2]); return 0 end", Long.class);

    private void ensureCache(Long commentId) {
        String key = LIKE_USERS_KEY_PREFIX + commentId;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) return;
        var persisted = commentLikeMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.musealgo.oj.community.entity.CommentLike>()
                .eq(com.musealgo.oj.community.entity.CommentLike::getCommentId, commentId));
        String temporary = key + ":restore:" + java.util.UUID.randomUUID();
        try {
            redisTemplate.opsForSet().add(temporary, EMPTY);
            redisTemplate.expire(temporary, Duration.ofDays(likeSetTtlDays));
            for (var row : persisted) redisTemplate.opsForSet().add(temporary, row.getUserId());
            // 其他请求先完成恢复或写入时，不用旧数据库快照覆盖它。
            redisTemplate.execute(INSTALL, java.util.List.of(key, temporary));
        } finally { redisTemplate.delete(temporary); }
    }


    // Redis Key 前缀
    private static final String LIKE_USERS_KEY_PREFIX = "comment:like:users:";
    private static final String DIRTY_COMMENTS_KEY = "comment:like:dirty_ids";

    /** 活跃点赞缓存保留时间；过期后从数据库恢复。 */
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
        if (userId == null) throw new BusinessException(CommonErrorCode.AUTH_REQUIRED, "请先登录");
        if (isLiked(commentId, userId)) deactivate(commentId, userId);
        else activate(commentId, userId);
    }

    @Override
    public boolean activate(Long commentId, Long userId) {
        if (commentId == null || userId == null) return false;
        ensureCache(commentId);
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
        ensureCache(commentId);
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
        ensureCache(commentId);
        Long size = redisTemplate.opsForSet().size(key);
        int sentinel = Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(key, EMPTY)) ? 1 : 0;
        return size != null ? Math.max(0, size.intValue() - sentinel) : 0;
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
        ensureCache(commentId);
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(LIKE_USERS_KEY_PREFIX + commentId, userId));
    }

    /**
     * 批量查询某评论的所有点赞用户ID (用于持久化)
     */
    @Override
    public Set<Object> getLikedUserIds(Long commentId) {
        ensureCache(commentId);
        Set<Object> members = redisTemplate.opsForSet().members(LIKE_USERS_KEY_PREFIX + commentId);
        if (members == null) return java.util.Set.of();
        members.remove(EMPTY);
        return members;
    }
}
