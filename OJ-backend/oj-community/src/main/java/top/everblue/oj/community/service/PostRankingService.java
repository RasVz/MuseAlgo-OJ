package top.everblue.oj.community.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import top.everblue.oj.common.utils.HotScoreCalculator;
import top.everblue.oj.common.utils.PostRedisKey;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 帖子热度排行域：热度分计算 / ZSet 排行读写 / 统计快照查询。
 * <p>
 * 2026-08-15 大服务类拆分：从 {@link PostHotService} 拆出（行为域委托本类重算排行）。
 * </p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PostRankingService {

    public static final String FIELD_VIEWS = "views";
    public static final String FIELD_LIKES = "likes";
    public static final String FIELD_DISLIKES = "dislikes";
    public static final String FIELD_FAVORITES = "favorites";
    public static final String FIELD_COMMENTS = "comments";

    public static final List<String> STAT_FIELDS = Arrays.asList(
            FIELD_VIEWS, FIELD_LIKES, FIELD_DISLIKES, FIELD_FAVORITES, FIELD_COMMENTS
    );

    /**
     * 条件更新排行，防止并发下旧分快照覆盖新分快照。
     * 只有当前 hash 统计与期望快照完全一致时，才更新 ZSet 分数。
     */
    private static final String UPDATE_RANK_IF_MATCH_LUA =
            "local statKey = KEYS[1] " +
                    "local globalKey = KEYS[2] " +
                    "local categoryKey = KEYS[3] " +
                    "local scoreKey = KEYS[4] " +

                    "local postId = ARGV[1] " +
                    "local hasCategory = ARGV[2] " +
                    "local expViews = tonumber(ARGV[3]) " +
                    "local expLikes = tonumber(ARGV[4]) " +
                    "local expDislikes = tonumber(ARGV[5]) " +
                    "local expFavorites = tonumber(ARGV[6]) " +
                    "local expComments = tonumber(ARGV[7]) " +
                    "local newScore = tonumber(ARGV[8]) " +

                    "local views = tonumber(redis.call('HGET', statKey, 'views') or '0') " +
                    "local likes = tonumber(redis.call('HGET', statKey, 'likes') or '0') " +
                    "local dislikes = tonumber(redis.call('HGET', statKey, 'dislikes') or '0') " +
                    "local favorites = tonumber(redis.call('HGET', statKey, 'favorites') or '0') " +
                    "local comments = tonumber(redis.call('HGET', statKey, 'comments') or '0') " +

                    "if views ~= expViews or likes ~= expLikes or dislikes ~= expDislikes or favorites ~= expFavorites or comments ~= expComments then " +
                    "  return 0 " +
                    "end " +

                    "redis.call('ZADD', globalKey, newScore, postId) " +
                    "if hasCategory == '1' then " +
                    "  redis.call('ZADD', categoryKey, newScore, postId) " +
                    "end " +
                    "redis.call('SET', scoreKey, tostring(newScore)) " +
                    "return 1";

    private final StringRedisTemplate redisTemplate;
    private final HotScoreCalculator calculator;

    private final DefaultRedisScript<Long> updateRankScript = buildLongScript(UPDATE_RANK_IF_MATCH_LUA);

    public List<Long> getHotPosts(String category, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(size, 1);

        String key = (category == null || category.trim().isEmpty() || "all".equalsIgnoreCase(category))
                ? PostRedisKey.hotGlobal()
                : PostRedisKey.hotCategory(category);

        long start = (long) (safePage - 1) * safeSize;
        long end = start + safeSize - 1;

        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(key, start, end);

        if (tuples == null || tuples.isEmpty()) {
            return Collections.emptyList();
        }

        return tuples.stream()
                .map(ZSetOperations.TypedTuple::getValue)
                .filter(Objects::nonNull)
                .map(Long::parseLong)
                .collect(Collectors.toList());
    }

    public long getHotPostTotal(String category) {
        String key = (category == null || category.trim().isEmpty() || "all".equalsIgnoreCase(category))
                ? PostRedisKey.hotGlobal()
                : PostRedisKey.hotCategory(category);
        Long size = redisTemplate.opsForZSet().zCard(key);
        return size == null ? 0L : size;
    }

    public Map<String, Integer> getPostStats(Long postId) {
        if (postId == null) {
            return defaultStatMap();
        }

        String statKey = PostRedisKey.stat(postId);
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(statKey);

        Map<String, Integer> result = defaultStatMap();
        if (entries == null || entries.isEmpty()) {
            return result;
        }

        for (String field : STAT_FIELDS) {
            Object value = entries.get(field);
            if (value != null) {
                result.put(field, safeToInt(value));
            }
        }
        return result;
    }

    /** 新帖初始化：写全零统计 + 按初始热度分入榜（全局 + 分类）。 */
    public void initNewPost(Long postId, String category, boolean isTop, boolean isEssence) {
        if (postId == null) {
            return;
        }

        Map<String, Integer> zeroStats = new HashMap<>();
        zeroStats.put(FIELD_VIEWS, 0);
        zeroStats.put(FIELD_LIKES, 0);
        zeroStats.put(FIELD_DISLIKES, 0);
        zeroStats.put(FIELD_FAVORITES, 0);
        zeroStats.put(FIELD_COMMENTS, 0);

        double initScore = calculator.calculate(zeroStats, isTop, isEssence, LocalDateTime.now());

        redisTemplate.opsForZSet().add(PostRedisKey.hotGlobal(), postId.toString(), initScore);
        if (category != null && !category.trim().isEmpty()) {
            redisTemplate.opsForZSet().add(PostRedisKey.hotCategory(category), postId.toString(), initScore);
        }
        redisTemplate.opsForValue().set(PostRedisKey.hotScore(postId), String.valueOf(initScore));
    }

    /** 按当前统计快照刷新某帖排行（行为变更后调用）。 */
    public void refreshRankingForPost(Long postId, String category,
                                      boolean isTop, boolean isEssence, LocalDateTime createTime) {
        if (postId == null) {
            return;
        }
        recalculateRanking(postId, category, getPostStats(postId), isTop, isEssence, createTime);
    }

    public void removeFromRanking(Long postId, String category) {
        if (postId == null) {
            return;
        }
        redisTemplate.opsForZSet().remove(PostRedisKey.hotGlobal(), postId.toString());
        if (category != null && !category.trim().isEmpty()) {
            redisTemplate.opsForZSet().remove(PostRedisKey.hotCategory(category), postId.toString());
        }
    }

    /**
     * 重算并更新排行（行为域委托入口）：
     * 先按期望快照条件更新（Lua 原子），失败重试一次读取最新统计，最后兜底直写。
     */
    public void recalculateRanking(Long postId, String category, Map<String, Integer> stats,
                                   boolean isTop, boolean isEssence, LocalDateTime createTime) {
        String statKey = PostRedisKey.stat(postId);
        String globalKey = PostRedisKey.hotGlobal();
        boolean hasCategory = category != null && !category.trim().isEmpty();
        String categoryKey = hasCategory ? PostRedisKey.hotCategory(category) : globalKey;
        String scoreKey = PostRedisKey.hotScore(postId);

        LocalDateTime safeCreateTime = createTime == null ? LocalDateTime.now() : createTime;
        Map<String, Integer> expected = fillMissingStats(stats);
        double score = calculator.calculate(expected, isTop, isEssence, safeCreateTime);

        for (int i = 0; i < 2; i++) {
            Long updated = redisTemplate.execute(
                    updateRankScript,
                    Arrays.asList(statKey, globalKey, categoryKey, scoreKey),
                    postId.toString(),
                    hasCategory ? "1" : "0",
                    String.valueOf(expected.get(FIELD_VIEWS)),
                    String.valueOf(expected.get(FIELD_LIKES)),
                    String.valueOf(expected.get(FIELD_DISLIKES)),
                    String.valueOf(expected.get(FIELD_FAVORITES)),
                    String.valueOf(expected.get(FIELD_COMMENTS)),
                    String.valueOf(score)
            );

            if (Long.valueOf(1L).equals(updated)) {
                return;
            }

            expected = getPostStats(postId);
            score = calculator.calculate(expected, isTop, isEssence, safeCreateTime);
        }

        redisTemplate.opsForZSet().add(globalKey, postId.toString(), score);
        if (hasCategory) {
            redisTemplate.opsForZSet().add(categoryKey, postId.toString(), score);
        }
        redisTemplate.opsForValue().set(scoreKey, String.valueOf(score));
    }

    private static DefaultRedisScript<Long> buildLongScript(String scriptText) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(scriptText);
        script.setResultType(Long.class);
        return script;
    }

    static int safeToInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception ex) {
            return 0;
        }
    }

    private static Map<String, Integer> fillMissingStats(Map<String, Integer> raw) {
        Map<String, Integer> result = defaultStatMap();
        if (raw == null) {
            return result;
        }
        for (String field : STAT_FIELDS) {
            Integer value = raw.get(field);
            if (value != null) {
                result.put(field, Math.max(0, value));
            }
        }
        return result;
    }

    private static Map<String, Integer> defaultStatMap() {
        Map<String, Integer> map = new HashMap<>();
        map.put(FIELD_VIEWS, 0);
        map.put(FIELD_LIKES, 0);
        map.put(FIELD_DISLIKES, 0);
        map.put(FIELD_FAVORITES, 0);
        map.put(FIELD_COMMENTS, 0);
        return map;
    }
}
