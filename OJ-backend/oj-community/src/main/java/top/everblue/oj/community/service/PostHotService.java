package top.everblue.oj.community.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import top.everblue.oj.common.utils.PostRedisKey;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 帖子热度行为域：点赞/点踩/收藏/浏览/评论计数（原子 Lua）+ 用户行为集合判定。
 * <p>
 * 2026-08-15 大服务类拆分：排行读写与热度分重算下沉 {@link PostRankingService}，
 * public 方法签名全部保留（7 个调用方零改动）。
 * </p>
 * <p>
 * <b>为什么用 Lua 而不用 WATCH/事务或多条命令：</b>
 * 一次互动要同时改「用户行为集合」（判断是否首次）与「计数 hash」（决定 +1 还是 +1）。
 * 拆成两条命令时，两个用户并发点赞同一帖会各自读到未变更的集合，
 * 出现集合只留一个成员而计数加了 2 的偏差；Lua 在 Redis 单线程内整体执行，
 * 天然规避了这个竞态，也省掉一次网络往返。
 * </p>
 * <p>
 * <b>浏览量为何用 HyperLogLog：</b>浏览是唯一的高频写路径，若用 Set 存每个 UV，
 * 热门帖的 key 会膨胀到与访问量同阶；HLL 以约 0.81% 的标准误差换固定大小内存。
 * 代价是 {@code views} 是<b>近似值</b>，不可用于精确计数或计费，
 * 且 {@code PFADD} 无法删除元素——用户删帖时只能整体丢弃该帖的 HLL key。
 * </p>
 * <p>
 * 计数以 Redis 为准，数据库中的 {@code post} 快照仅在 key 被淘汰后用于补种
 * （见 {@link #initializeStatsIfAbsent}），因此极端情况下读到的数字会短暂回退。
 * </p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PostHotService {

    /**
     * 原子操作：
     * 1) 对用户行为集合做 ADD/REMOVE/TOGGLE
     * 2) 同步更新 hash 中对应计数（不会出现负数）
     * 3) 返回变更标记、delta 及最新统计快照
     */
    private static final String APPLY_ACTION_AND_GET_STATS_LUA =
            "local setKey = KEYS[1] " +
                    "local statKey = KEYS[2] " +
                    "local member = ARGV[1] " +
                    "local field = ARGV[2] " +
                    "local action = ARGV[3] " +
                    "local changed = 0 " +
                    "local delta = 0 " +

                    "if action == 'ADD' then " +
                    "  local added = redis.call('SADD', setKey, member) " +
                    "  if added == 1 then changed = 1 delta = 1 end " +
                    "elseif action == 'REMOVE' then " +
                    "  local removed = redis.call('SREM', setKey, member) " +
                    "  if removed == 1 then changed = 1 delta = -1 end " +
                    "elseif action == 'TOGGLE' then " +
                    "  local existed = redis.call('SISMEMBER', setKey, member) " +
                    "  if existed == 1 then " +
                    "    redis.call('SREM', setKey, member) " +
                    "    changed = 1 delta = -1 " +
                    "  else " +
                    "    redis.call('SADD', setKey, member) " +
                    "    changed = 1 delta = 1 " +
                    "  end " +
                    "end " +

                    "if delta ~= 0 then " +
                    "  local curr = tonumber(redis.call('HGET', statKey, field) or '0') " +
                    "  curr = curr + delta " +
                    "  if curr < 0 then curr = 0 end " +
                    "  redis.call('HSET', statKey, field, tostring(curr)) " +
                    "end " +

                    "local views = tonumber(redis.call('HGET', statKey, 'views') or '0') " +
                    "local likes = tonumber(redis.call('HGET', statKey, 'likes') or '0') " +
                    "local dislikes = tonumber(redis.call('HGET', statKey, 'dislikes') or '0') " +
                    "local favorites = tonumber(redis.call('HGET', statKey, 'favorites') or '0') " +
                    "local comments = tonumber(redis.call('HGET', statKey, 'comments') or '0') " +
                    "return {changed, delta, views, likes, dislikes, favorites, comments}";

    private final StringRedisTemplate redisTemplate;
    private final PostRankingService rankingService;

    private final DefaultRedisScript<List> applyActionScript = buildListScript(APPLY_ACTION_AND_GET_STATS_LUA);

    public boolean like(Long postId, Long userId, String category,
                        boolean isTop, boolean isEssence, LocalDateTime createTime) {
        if (postId == null || userId == null) {
            return false;
        }

        ensureStatInitialized(postId);

        ActionResult actionResult = applyActionAndReadStats(
                PostRedisKey.likedUsers(postId), postId, userId, PostRankingService.FIELD_LIKES, "ADD"
        );
        if (actionResult == null || !actionResult.changed) {
            return false;
        }

        rankingService.recalculateRanking(postId, category, actionResult.stats, isTop, isEssence, createTime);
        return true;
    }

    /**
     * Seeds an evicted Redis statistics hash from the durable post snapshot.
     * <p>
     * 补种被淘汰的统计 hash。
     * 用 {@code putIfAbsent} 而非 {@code put}：Redis 可能因内存淘汰而在别的字段上
     * 已回收了部分数据，直接 put 会把仍然存在的有效计数覆盖成数据库里的旧快照，
     * 造成已累积的互动数被抹平。
     * </p>
     */
    public void initializeStatsIfAbsent(Long postId, Map<String, Integer> persistedStats) {
        if (postId == null || persistedStats == null) return;
        String statKey = PostRedisKey.stat(postId);
        for (String field : PostRankingService.STAT_FIELDS) {
            redisTemplate.opsForHash().putIfAbsent(statKey, field,
                    String.valueOf(Math.max(0, persistedStats.getOrDefault(field, 0))));
        }
    }

    public boolean unlike(Long postId, Long userId, String category,
                          boolean isTop, boolean isEssence, LocalDateTime createTime) {
        if (postId == null || userId == null) {
            return false;
        }

        ensureStatInitialized(postId);

        ActionResult actionResult = applyActionAndReadStats(
                PostRedisKey.likedUsers(postId), postId, userId, PostRankingService.FIELD_LIKES, "REMOVE"
        );
        if (actionResult == null || !actionResult.changed) {
            return false;
        }

        rankingService.recalculateRanking(postId, category, actionResult.stats, isTop, isEssence, createTime);
        return true;
    }

    public boolean dislike(Long postId, Long userId, String category,
                           boolean isTop, boolean isEssence, LocalDateTime createTime) {
        if (postId == null || userId == null) {
            return false;
        }

        ensureStatInitialized(postId);

        ActionResult actionResult = applyActionAndReadStats(
                PostRedisKey.dislikedUsers(postId), postId, userId, PostRankingService.FIELD_DISLIKES, "ADD"
        );
        if (actionResult == null || !actionResult.changed) {
            return false;
        }

        rankingService.recalculateRanking(postId, category, actionResult.stats, isTop, isEssence, createTime);
        return true;
    }

    public boolean undislike(Long postId, Long userId, String category,
                             boolean isTop, boolean isEssence, LocalDateTime createTime) {
        if (postId == null || userId == null) {
            return false;
        }

        ensureStatInitialized(postId);

        ActionResult actionResult = applyActionAndReadStats(
                PostRedisKey.dislikedUsers(postId), postId, userId, PostRankingService.FIELD_DISLIKES, "REMOVE"
        );
        if (actionResult == null || !actionResult.changed) {
            return false;
        }

        rankingService.recalculateRanking(postId, category, actionResult.stats, isTop, isEssence, createTime);
        return true;
    }

    public boolean favorite(Long postId, Long userId, String category,
                            boolean isTop, boolean isEssence, LocalDateTime createTime) {
        if (postId == null || userId == null) {
            return false;
        }

        ensureStatInitialized(postId);

        ActionResult actionResult = applyActionAndReadStats(
                PostRedisKey.favedUsers(postId), postId, userId, PostRankingService.FIELD_FAVORITES, "ADD"
        );
        if (actionResult == null || !actionResult.changed) {
            return false;
        }

        rankingService.recalculateRanking(postId, category, actionResult.stats, isTop, isEssence, createTime);
        return true;
    }

    public boolean unfavorite(Long postId, Long userId, String category,
                              boolean isTop, boolean isEssence, LocalDateTime createTime) {
        if (postId == null || userId == null) {
            return false;
        }

        ensureStatInitialized(postId);

        ActionResult actionResult = applyActionAndReadStats(
                PostRedisKey.favedUsers(postId), postId, userId, PostRankingService.FIELD_FAVORITES, "REMOVE"
        );
        if (actionResult == null || !actionResult.changed) {
            return false;
        }

        rankingService.recalculateRanking(postId, category, actionResult.stats, isTop, isEssence, createTime);
        return true;
    }

    /**
     * 浏览量按 UV 记录（HyperLogLog）。
     * 兼顾游客登录状态下，兼容 null 传参记录统配浏览量
     * <p>
     * 注意：所有游客写入同一个 {@code anonymous} 成员，因此 N 个游客只计 1 个 UV。
     * 这是 HLL + 无身份标识的固有限制，不是 bug；若产品要求区分游客 UV，
     * 需引入 IP 或会话标识并评估其隐私合规性。
     * </p>
     *
     * @param userId 为 {@code null} 时按游客处理
     */
    public void view(Long postId, Long userId) {
        if (postId == null) {
            return;
        }

        ensureStatInitialized(postId);

        String viewKey = PostRedisKey.viewedUsers(postId);
        String statKey = PostRedisKey.stat(postId);

        if (userId != null) {
            redisTemplate.opsForHyperLogLog().add(viewKey, userId.toString());
        } else {
            // 游客统计入相同的 HLL 时统一算作 anonymous (可基于 IP 改进以精确计算游客UV)
            redisTemplate.opsForHyperLogLog().add(viewKey, "anonymous");
        }

        Long uvCount = redisTemplate.opsForHyperLogLog().size(viewKey);
        if (uvCount != null) {
            redisTemplate.opsForHash().put(statKey, PostRankingService.FIELD_VIEWS, uvCount.toString());
        }
    }

    public void initNewPost(Long postId, String category, boolean isTop, boolean isEssence) {
        rankingService.initNewPost(postId, category, isTop, isEssence);
    }

    /** Adds one persisted comment to the live statistics and refreshes the ranking. */
    public void recordComment(Long postId, String category, boolean isTop, boolean isEssence,
                              LocalDateTime createTime) {
        if (postId == null) return;
        ensureStatInitialized(postId);
        redisTemplate.opsForHash().increment(PostRedisKey.stat(postId), PostRankingService.FIELD_COMMENTS, 1);
        rankingService.recalculateRanking(postId, category, rankingService.getPostStats(postId),
                isTop, isEssence, createTime);
    }

    // ======================== 排行域委托 ========================

    public List<Long> getHotPosts(String category, int page, int size) {
        return rankingService.getHotPosts(category, page, size);
    }

    public long getHotPostTotal(String category) {
        return rankingService.getHotPostTotal(category);
    }

    public Map<String, Integer> getPostStats(Long postId) {
        return rankingService.getPostStats(postId);
    }

    public boolean hasLiked(Long postId, Long userId) {
        if (postId == null || userId == null) {
            return false;
        }
        return Boolean.TRUE.equals(
                redisTemplate.opsForSet().isMember(PostRedisKey.likedUsers(postId), userId.toString())
        );
    }

    public boolean hasFaved(Long postId, Long userId) {
        if (postId == null || userId == null) {
            return false;
        }
        return Boolean.TRUE.equals(
                redisTemplate.opsForSet().isMember(PostRedisKey.favedUsers(postId), userId.toString())
        );
    }

    public boolean hasDisliked(Long postId, Long userId) {
        if (postId == null || userId == null) {
            return false;
        }
        return Boolean.TRUE.equals(
                redisTemplate.opsForSet().isMember(PostRedisKey.dislikedUsers(postId), userId.toString())
        );
    }

    public void refreshRankingForPost(Long postId, String category,
                                      boolean isTop, boolean isEssence, LocalDateTime createTime) {
        rankingService.refreshRankingForPost(postId, category, isTop, isEssence, createTime);
    }

    public void removeFromRanking(Long postId, String category) {
        rankingService.removeFromRanking(postId, category);
    }

    // ======================== 私有辅助 ========================

    /**
     * 执行 Lua 变更并取回最新统计快照。
     * <p>
     * 返回 {@code null} 表示脚本返回值结构异常（Redis 不可用、返回被截断），
     * 调用方一律按「无变化」处理——宁可这次互动没生效，也不让异常冒泡把
     * 用户请求打断，此时数据已在 Redis 侧保持自洽。
     * </p>
     *
     * @param action {@code ADD} / {@code REMOVE} / {@code TOGGLE}
     * @return 变更标记、增量与五个计数的快照；异常时为 {@code null}
     */
    private ActionResult applyActionAndReadStats(String setKey, Long postId, Long userId, String field, String action) {
        String statKey = PostRedisKey.stat(postId);

        List<?> luaResult = redisTemplate.execute(
                applyActionScript,
                Arrays.asList(setKey, statKey),
                userId.toString(), field, action
        );

        if (luaResult == null || luaResult.size() < 7) {
            log.warn("Lua返回异常, postId={}, action={}, result={}", postId, action, luaResult);
            return null;
        }

        ActionResult result = new ActionResult();
        result.changed = safeToInt(luaResult.get(0)) == 1;
        result.delta = safeToInt(luaResult.get(1));

        Map<String, Integer> stats = new HashMap<>();
        stats.put(PostRankingService.FIELD_VIEWS, safeToInt(luaResult.get(2)));
        stats.put(PostRankingService.FIELD_LIKES, safeToInt(luaResult.get(3)));
        stats.put(PostRankingService.FIELD_DISLIKES, safeToInt(luaResult.get(4)));
        stats.put(PostRankingService.FIELD_FAVORITES, safeToInt(luaResult.get(5)));
        stats.put(PostRankingService.FIELD_COMMENTS, safeToInt(luaResult.get(6)));
        result.stats = stats;

        return result;
    }

    private void ensureStatInitialized(Long postId) {
        String statKey = PostRedisKey.stat(postId);
        for (String field : PostRankingService.STAT_FIELDS) {
            redisTemplate.opsForHash().putIfAbsent(statKey, field, "0");
        }
    }

    private static DefaultRedisScript<List> buildListScript(String scriptText) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptText(scriptText);
        script.setResultType(List.class);
        return script;
    }

    private static int safeToInt(Object value) {
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

    private static class ActionResult {
        private boolean changed;
        private int delta;
        private Map<String, Integer> stats;
    }
}
