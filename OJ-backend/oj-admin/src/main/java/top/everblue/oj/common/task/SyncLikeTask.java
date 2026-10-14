package top.everblue.oj.common.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 点赞 Redis → MySQL 定时同步任务。
 * <p>
 * 事务边界：每 30s 拉一批 dirty 评论，逐条委托 {@link LikeSyncItemProcessor}
 * 独立事务落库；单条失败将 dirty 标记加回 Redis 并中断本轮（该条下轮重试），
 * 不污染同批其他评论。
 */
@Slf4j
@Component
public class SyncLikeTask {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private LikeSyncItemProcessor likeSyncItemProcessor;

    private static final String DIRTY_COMMENTS_KEY = "comment:like:dirty_ids";

    // 每 30 秒执行一次 (cron 表达式)
    @Scheduled(cron = "0/30 * * * * ?")
    public void syncLikesToDB() {
        // 1. 从 Redis 取出所有发生过变化的 commentId
        Set<Object> dirtyIds = redisTemplate.opsForSet().members(DIRTY_COMMENTS_KEY);
        if (dirtyIds == null || dirtyIds.isEmpty()) return;

        log.info("开始同步点赞数据，涉及评论数: {}", dirtyIds.size());

        for (Object objId : dirtyIds) {
            // Claim this marker before taking the snapshot. Any new like/unlike
            // during persistence adds it back, so the next run cannot lose it.
            redisTemplate.opsForSet().remove(DIRTY_COMMENTS_KEY, objId);
            Long commentId = LikeSyncItemProcessor.toLong(objId);
            if (commentId == null) {
                log.warn("忽略无法识别的评论点赞同步标记: {}", objId);
                continue;
            }

            try {
                // 2. 单条独立事务落库（失败只回滚本条）
                likeSyncItemProcessor.syncOne(commentId);
            } catch (RuntimeException e) {
                redisTemplate.opsForSet().add(DIRTY_COMMENTS_KEY, objId);
                log.error("评论点赞同步失败，已放回重试队列: commentId={}", commentId, e);
                throw e;
            }
        }
        log.info("点赞数据同步完成");
    }
}
