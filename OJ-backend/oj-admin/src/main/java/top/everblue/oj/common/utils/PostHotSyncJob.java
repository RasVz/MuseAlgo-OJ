package top.everblue.oj.common.utils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.everblue.oj.community.entity.Post;
import top.everblue.oj.community.mapper.PostMapper;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 帖子热度榜的周期性重算任务。
 * <p>
 * 热度分里含时间衰减，值会随时间自然下滑——只靠互动时的增量更新，榜单会冻结在「上次有人点赞时」的样子。
 * 本任务每 10 分钟把近期活跃帖子重新算一遍并刷回，让热度榜随时间自然沉底。
 * </p>
 * <p>
 * 三道省力措施都是为了不让定时任务变成数据库压力源：
 * 只处理近 {@code SYNC_DAYS} 天有互动的帖子（更老的帖子分数早已衰减到榜外，重算没有意义）、
 * 按 {@code BATCH_SIZE} 分页而不是一次全量加载、以及最后对榜单 ZSet 做长尾裁剪。
 * <strong>调大任何一项之前先想清楚这条 SQL 会带走多少行。</strong>
 * </p>
 * <p>
 * 数据源以 Redis 计数 Hash 为准，Redis 为空（冷数据被清理过）时才回落到 MySQL 补录——
 * 互动计数是 Redis 侧的实时值，MySQL 只是用于后台分析的快照，用错主次会导致分数抖动。
 * </p>
 * <p>
 * 榜尾裁剪是<strong>有损</strong>操作：被裁掉的帖子下次有互动时会被重新加回 ZSet，
 * 分数重算而非历史延续。这是刻意接受的——省内存比「长尾帖保持原排名」更重要。
 * </p>
 * <p>
 * 这里硬编码了分类枚举（algorithm/life/leisure）。新增分类时必须同步补进裁剪列表，
 * 否则新分类的 ZSet 只增不减，会成为只涨不落的内存泄漏点。
 * </p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PostHotSyncJob {

    private final StringRedisTemplate redisTemplate;
    private final HotScoreCalculator calculator;
    private final PostMapper postMapper; // MyBatisPlus Mapper

    // 只处理近7天有互动的帖子，减少计算量
    private static final int SYNC_DAYS = 7;
    // 每批处理数量
    private static final int BATCH_SIZE = 500;

    /**
     * 每10分钟执行一次：重新计算时间衰减 + 清理长尾 + 刷回MySQL
     */
    @Scheduled(fixedDelay = 10 * 60 * 1000) // 10分钟
    public void syncHotScore() {
        log.info("开始同步帖子热度...");
        long start = System.currentTimeMillis();

        // 1. 从MySQL获取近期有更新的帖子（带分页）
        LocalDateTime since = LocalDateTime.now().minusDays(SYNC_DAYS);
        int page = 1;
        int updatedCount = 0;

        while (true) {
            // 查询近期有互动的帖子（假设你有update_time索引）
            List<Post> posts = postMapper.selectRecentActivePosts(since, (page - 1) * BATCH_SIZE, BATCH_SIZE);
            if (posts.isEmpty()) break;

            for (Post post : posts) {
                try {
                    syncSinglePost(post);
                    updatedCount++;
                } catch (Exception e) {
                    log.error("同步帖子{}热度失败", post.getId(), e);
                }
            }

            if (posts.size() < BATCH_SIZE) break;
            page++;
        }

        // 2. 清理ZSet长尾（只保留Top 1000）
        trimHotZSet(PostRedisKey.hotGlobal(), 1000);
        // 清理各分类（假设category枚举已知）
        Arrays.asList("algorithm", "life", "leisure").forEach(cat ->
                trimHotZSet(PostRedisKey.hotCategory(cat), 500)
        );

        log.info("热度同步完成，更新{}条，耗时{}ms", updatedCount, System.currentTimeMillis() - start);
    }

    private void syncSinglePost(Post post) {
        Long postId = post.getId();
        String statKey = PostRedisKey.stat(postId);

        // 读取Redis当前统计（作为基准）
        Map<Object, Object> redisStats = redisTemplate.opsForHash().entries(statKey);

        // 如果Redis没有（冷数据），从MySQL补录
        if (redisStats.isEmpty()) {
            redisTemplate.opsForHash().putAll(statKey, Map.of(
                    "views", String.valueOf(post.getViews()),
                    "likes", String.valueOf(post.getLikes()),
                    "dislikes", String.valueOf(post.getDislikes()),
                    "favorites", String.valueOf(post.getFavorites()),
                    "comments", String.valueOf(post.getCommentCount())
            ));
        }

        // 重新计算分数（带入当前时间，实现时间衰减）
        Map<String, Integer> stats = new HashMap<>();
        stats.put("views", Integer.parseInt(redisStats.getOrDefault("views", "0").toString()));
        stats.put("likes", Integer.parseInt(redisStats.getOrDefault("likes", "0").toString()));
        stats.put("dislikes", Integer.parseInt(redisStats.getOrDefault("dislikes", "0").toString()));
        stats.put("favorites", Integer.parseInt(redisStats.getOrDefault("favorites", "0").toString()));
        stats.put("comments", Integer.parseInt(redisStats.getOrDefault("comments", "0").toString()));

        double newScore = calculator.calculate(stats, post.getIsTop() == 1, post.getIsEssence() == 1,
                post.getCreateTime());

        // 更新ZSet（强制重算衰减后的分数）
        redisTemplate.opsForZSet().add(PostRedisKey.hotGlobal(), postId.toString(), newScore);
        if (post.getCategory() != null) {
            redisTemplate.opsForZSet().add(
                    PostRedisKey.hotCategory(post.getCategory()), postId.toString(), newScore
            );
        }

        // 刷回MySQL（最终一致性，供后台分析使用）
        postMapper.updateHotScore(postId, newScore, LocalDateTime.now());
    }

    private void trimHotZSet(String key, int keepSize) {
        Long currentSize = redisTemplate.opsForZSet().zCard(key);
        if (currentSize != null && currentSize > keepSize) {
            // 移除排名 keepSize 之后的所有元素
            redisTemplate.opsForZSet().removeRange(key, 0, currentSize - keepSize - 1);
        }
    }
}
