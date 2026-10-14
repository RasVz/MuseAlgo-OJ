package top.everblue.oj.contest.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;
import top.everblue.oj.contest.entity.Contest;
import top.everblue.oj.contest.entity.vo.ContestRankVO;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 比赛实时榜单的 Redis 存储与排序实现。
 * <p>
 * 榜单是「读多写频」的热点数据：比赛进行中每次提交都要重算并让所有观众看到最新名次，
 * 而绝大多数请求是在读榜。因此这里用 Redis ZSet 承担排序，把 MySQL 留给赛后归档，
 * 避免高频查询把数据库打满。
 * </p>
 *
 * <h3>Redis Key 布局</h3>
 * <pre>
 * contest:rank:{contestId}                          ZSet   userId → 排序分（榜单本体）
 * contest:rank:{contestId}:user:{userId}             Hash   solvedCount / penaltyTime / totalScore
 * contest:rank:{contestId}:user:{userId}:problem:{pid} Hash  solved / firstAcTime / attemptCount / score
 * </pre>
 * Hash 里的数值一律以<b>字符串</b>存取。Redis Hash 的 value 只是二进制容器，
 * 没有字段级类型，存数字反而要在每次读取时解析；统一存字符串让读写路径对称，
 * 也让「字段不存在」与「字段为 0」能通过 {@code parseInt(x, 0)} 收敛成同一种默认值。
 * </p>
 *
 * <h3>排序分编码：把两个关键字塞进一个 double</h3>
 * <p>
 * 竞赛模式的名次由「解题数降序 + 罚时升序」决定，是二元排序。Redis ZSet 只支持一维 score，
 * 于是编码为 {@code solvedCount * 10^9 - penaltyTime}：
 * <ul>
 *   <li>基数 10^9 大于任何一场比赛的总罚时（题目数 × 开赛至今分钟数 + 错题 × 20），
 *       保证「多解一题」永远压过「一题少罚几个小时」，两条排序规则被压进同一个标量比较。</li>
 *   <li>乘基数为正、减罚时为负，score 单调<b>越大越好</b>，于是取榜只需 {@code reverseRange}，
 *       不必像 ASC 方案那样把分取负。</li>
 * </ul>
 * 代价是 score 只在 2^53 精确整数范围内可信；按 10^9 基数，上限约 900 万道题，远超现实比赛规模。
 * </p>
 *
 * <h3>一致性取舍：接受最终一致</h3>
 * <p>
 * 更新路径是多次独立的 Redis 命令（先写 problem hash，再改 user hash，最后 ZADD），
 * <b>没有放在事务或 Lua 脚本里</b>。这是刻意的：判题结果本身可以重投，
 * 幂等性由 {@link #submitProblem} 中「已 solved 则直接返回」「OI 取历史最高分」两道判断保证，
 * 中间态最坏是某次推送里名次短暂不一致，下一次提交就会被修正。
 * 用 MULTI 包起来反而会拖长锁持有时间，且这些命令本身在 Redis 单线程下已天然串行，
 * 并不会产生「脏写」，只是缺少原子可见性——而榜单可以接受这一点。
 * </p>
 *
 * <h3>与数据库的关系</h3>
 * <p>
 * Redis 中的榜单 key 统一设 {@code CACHE_EXPIRE_DAYS}（7 天）过期，
 * 到期即释放，不做显式清理；比赛结束后由 {@link #deleteContestRankCache} 按需主动删除。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class RankRedisService {

    /**
     * 排序分基数，同时充当「一题的解题数权重」，见类注释「排序分编码」。
     * 必须大于任何可能的最大罚时，否则两关键字会在进位时互相干扰。
     */
    private static final long SCORE_BASE = 1_000_000_000L;

    /**
     * 竞赛模式每提交一次未通过的题目所加的罚时（分钟），ICPC 常规取值 20。
     * 只对「最终 AC 之前」的失败计数：{@link #submitProblem} 命中已 solved 分支时直接返回，
     * 因此 AC 之后的提交不会再追加罚时。
     */
    private static final int WRONG_PENALTY_MINUTES = 20;

    /** 榜单缓存保留天数。过期即释放，不依赖显式清理。 */
    private static final long CACHE_EXPIRE_DAYS = 7L;

    /** Redis key 统一前缀，见类注释「Redis Key 布局」。 */
    private static final String KEY_PREFIX = "contest:rank:";

    private final RedisTemplate<String, Object> redisTemplate;

    private final ContestService contestService;

    /**
     * 记录一次提交对榜单的影响，是本类唯一的写入口。
     * <p>
     * 按比赛模式分派：竞赛模式维护「解题数 + 罚时」，OI 模式维护「各题最高分之和」。
     * </p>
     *
     * <p>
     * <b>幂等：</b>重复调用同一 (contestId, userId, problemId) 不会重复计分——
     * 竞赛模式靠 problem hash 的 {@code solved} 标记短路，OI 模式靠 score 只增不减的判断短路。
     * 这正是消息消费端可以放心重试的前提。
     * </p>
     *
     * <p>
     * <b>容错：</b>关键参数缺失或比赛查不到时静默 {@code return}，不抛异常。
     * 榜单是可降级的展示数据，一条脏消息不应让消费线程反复重试。
     * </p>
     *
     * @param contestId 比赛 ID，为 null 时直接忽略（非比赛提交）
     * @param userId 参赛者 ID
     * @param problemId 题目 ID
     * @param status 判题结论，如 {@code AC}；竞赛模式仅 {@code AC} 计分
     * @param score 本次得分，OI 模式按此更新历史最高分；竞赛模式忽略
     * @param submitTime 提交时间，罚时以「距开赛分钟数」计，故必须提供
     */
    public void submitProblem(Long contestId, Long userId, Long problemId, String status,
                              int score, LocalDateTime submitTime) {
        if (contestId == null || userId == null || problemId == null || submitTime == null) {
            return;
        }

        Contest contest = contestService.getById(contestId);
        if (contest == null) {
            return;
        }
        boolean oiMode = "OI".equalsIgnoreCase(contest.getMode());

        String rankKey = getRankKey(contestId);
        String userKey = getUserKey(contestId, userId);
        String problemKey = getProblemKey(contestId, userId, problemId);

        initUserIfAbsent(userKey, userId);

        if (oiMode) {
            handleOiSubmission(rankKey, userKey, problemKey, userId, score);
            expireContestKeys(contestId, userId, problemId);
            return;
        }

        Map<Object, Object> problemData = redisTemplate.opsForHash().entries(problemKey);
        boolean solved = "1".equals(String.valueOf(problemData.getOrDefault("solved", "0")));
        // 幂等闸门：该题此前已 AC 过。罚时只增不减，重算一次是幂等操作，
        // 但这里直接返回可以省掉 AC 之后每次提交的多次 Redis 往返。
        // 注意：AC 之后的提交<b>不</b>累加错题罚时，这与 ICPC 规则一致。
        if (solved) {
            refreshUserRankScore(contestId, userId);
            expireContestKeys(contestId, userId, problemId);
            return;
        }

        if ("AC".equalsIgnoreCase(status)) {
            // 罚时 = 距开赛分钟数 + 该题此前失败次数 × 20。
            // attemptCount 取的是本次 AC <b>之前</b>已累计的失败数（下方原样写回，未 +1）。
            long firstAcMinutes = calculateMinutesFromContestStart(contest, submitTime);
            int attemptCount = parseInt(problemData.get("attemptCount"), 0);

            redisTemplate.opsForHash().put(problemKey, "solved", "1");
            redisTemplate.opsForHash().put(problemKey, "firstAcTime", String.valueOf(firstAcMinutes));
            redisTemplate.opsForHash().put(problemKey, "attemptCount", String.valueOf(attemptCount));

            int solvedCount = parseInt(redisTemplate.opsForHash().get(userKey, "solvedCount"), 0) + 1;
            int penaltyTime = parseInt(redisTemplate.opsForHash().get(userKey, "penaltyTime"), 0)
                    + (int) firstAcMinutes + attemptCount * WRONG_PENALTY_MINUTES;

            redisTemplate.opsForHash().put(userKey, "solvedCount", String.valueOf(solvedCount));
            redisTemplate.opsForHash().put(userKey, "penaltyTime", String.valueOf(penaltyTime));

            long rankScore = buildRankScore(solvedCount, penaltyTime);
            redisTemplate.opsForZSet().add(rankKey, String.valueOf(userId), (double) rankScore);
        } else {
            // 未通过：只累计失败次数。仍需刷新 ZSet 分数并非必要（罚时未变），
            // 但失败次数要到该题 AC 时才计入，此处刷新可让「已尝试」选手尽早出现在榜上（score 同为 0 时靠 userId 排序）
            int attemptCount = parseInt(problemData.get("attemptCount"), 0) + 1;
            redisTemplate.opsForHash().put(problemKey, "attemptCount", String.valueOf(attemptCount));
            redisTemplate.opsForHash().put(problemKey, "solved", "0");

            refreshUserRankScore(contestId, userId);
        }

        expireContestKeys(contestId, userId, problemId);
    }

    /**
     * OI 模式（ADR-0003）：每题取历史最高分，用户总分为各题最高分之和；
     * ZSet 分数即总分，同分由调用方按标准竞赛排名（1224）并列。
     */
    private void handleOiSubmission(String rankKey, String userKey, String problemKey,
                                    Long userId, int score) {
        // 0 分视为未得分：OI 模式下判题失败或得 0 分不应影响总分，也不该建立最高分基线
        if (score <= 0) {
            return;
        }
        int previousBest = parseInt(redisTemplate.opsForHash().get(problemKey, "score"), 0);
        // 只保留最高分，重复提交不重复计分。
        // 注意用「增量」而非「总分重算」：OI 模式下同一题可能提交多次，
        // 记 delta 可以避免因并发提交导致的读-改-写覆盖（先读到旧值再写回新总分）
        if (score <= previousBest) {
            return;
        }
        int delta = score - previousBest;
        int totalScore = parseInt(redisTemplate.opsForHash().get(userKey, "totalScore"), 0) + delta;
        redisTemplate.opsForHash().put(problemKey, "score", String.valueOf(score));
        redisTemplate.opsForHash().put(userKey, "totalScore", String.valueOf(totalScore));
        redisTemplate.opsForZSet().add(rankKey, String.valueOf(userId), (double) totalScore);
    }

    /**
     * 取榜单前 n 名并生成完整视图对象。
     * <p>
     * ZSet 只保存排序分，解题数、罚时等字段要从 user hash 逐个读出，
     * 因此这里是 n+1 次往返（1 次 topN + n 次 score + n 次 hash）。
     * 榜单人数在数十~数百量级，这个代价可以接受；真正需要优化时应改用 pipeline 批量取，
     * 而不是缓存整张榜（榜单随提交持续变化，缓存命中率低且不一致窗口大）。
     * </p>
     *
     * @param contestId 比赛 ID，为 null 时返回空列表
     * @param n 取前几名，{@code n <= 0} 时返回空列表（不视为「全部」）
     * @return 按名次升序的榜单，同分并列；无数据时返回空列表而非 null
     */
    public List<ContestRankVO> getTopN(Long contestId, int n) {
        if (contestId == null || n <= 0) {
            return Collections.emptyList();
        }

        String rankKey = getRankKey(contestId);
        Set<Object> userIds = redisTemplate.opsForZSet().reverseRange(rankKey, 0, n - 1);
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<ContestRankVO> result = new ArrayList<>();
        int index = 0;
        int rank = 1;
        Double lastScore = null;
        for (Object userIdObj : userIds) {
            Long userId = parseLong(userIdObj, null);
            if (userId == null) {
                continue;
            }
            Double zScore = redisTemplate.opsForZSet().score(rankKey, String.valueOf(userId));
            // 并列名次 = 标准竞赛排名（1224）：名次等于「排在前面的人数 + 1」，
            // 所以同分时沿用上一个名次且不推进 index，下一名自然是 index+1，从而出现 1,1,3 而非 1,1,2。
            // 用 Double.equals 比较分数：ZSet 存的就是同一个 double 位模式，
            // 换成数值容差比较反而可能把「分数本就接近但不相等」的两者误判为并列。
            if (lastScore == null || !lastScore.equals(zScore)) {
                rank = index + 1; // 同分并列（标准竞赛排名）
                lastScore = zScore;
            }
            result.add(buildRankVO(contestId, userId, rank));
            index++;
        }
        return result;
    }

    /**
     * 反查单个选手的名次，不遍历全榜。
     * <p>
     * ZSet 分数本身已编码了「解题数 + 罚时」，因此名次 = 「分数严格高于我的人数 + 1」，
     * 用 {@code count} 直接在服务端统计，无需拉回全榜在内存里数——O(log N) 取代 O(N) 次往返。
     * 同分选手得到相同名次，与 {@link #getAllRanks} 的并列规则一致。
     * </p>
     *
     * @param contestId 比赛 ID
     * @param userId 选手 ID
     * @return 名次（从 1 开始）；参数为 null、选手从未提交过（ZSet 中无成员）时返回 {@code null}
     */
    public Long getUserRank(Long contestId, Long userId) {
        if (contestId == null || userId == null) {
            return null;
        }

        String rankKey = getRankKey(contestId);
        Double score = redisTemplate.opsForZSet().score(rankKey, String.valueOf(userId));
        if (score == null) {
            // 区分「没上榜」与「排第一」：ZSet 无此成员即从未提交，不能返回 0 或 1
            return null;
        }
        // 用 Math.nextUp(score) 作为下界保证「严格大于」，把同分选手排除在计数之外
        Long usersWithHigherScore = redisTemplate.opsForZSet()
                .count(rankKey, Math.nextUp(score), Double.POSITIVE_INFINITY);
        return usersWithHigherScore == null ? null : usersWithHigherScore + 1;
    }

    /**
     * 取整张榜单，用于赛后归档、导出与页面全量刷新。
     * <p>
     * 与 {@link #getTopN} 是同一套并列名次逻辑的完整版，仅取数范围不同；
     * 并列判定代码重复而非抽公共方法，是因为两者的返回值都直接依赖循环内的 index/rank 状态，
     * 抽成方法需要把这几个量拆成参数或包装对象，可读性反而更差。
     * </p>
     *
     * @param contestId 比赛 ID，为 null 时返回空列表
     * @return 全榜，按名次升序、同分并列；无数据时返回空列表
     */
    public List<ContestRankVO> getAllRanks(Long contestId) {
        if (contestId == null) {
            return Collections.emptyList();
        }

        String rankKey = getRankKey(contestId);
        Set<Object> userIds = redisTemplate.opsForZSet().reverseRange(rankKey, 0, -1);
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<ContestRankVO> result = new ArrayList<>();
        int index = 0;
        int rank = 1;
        Double lastScore = null;
        for (Object userIdObj : userIds) {
            Long userId = parseLong(userIdObj, null);
            if (userId == null) {
                continue;
            }
            Double zScore = redisTemplate.opsForZSet().score(rankKey, String.valueOf(userId));
            if (lastScore == null || !lastScore.equals(zScore)) {
                rank = index + 1; // 同分并列（标准竞赛排名）
                lastScore = zScore;
            }
            result.add(buildRankVO(contestId, userId, rank));
            index++;
        }
        return result;
    }

    /**
     * 删除一场比赛的全部榜单缓存，用于比赛结束或管理员强制重算。
     * <p>
     * key 按比赛分片，没有「一键删某场比赛所有 key」的原子操作，只能先枚举再批量删。
     * 枚举分两步：ZSet 的成员列表是精确已知的，直接 range 取出；而
 * *「某用户下所有题目的 problem hash」没有索引，只能用 SCAN 扫前缀。
     * </p>
     *
     * @param contestId 比赛 ID，为 null 时不做任何事（幂等，重复调用安全）
     */
public void deleteContestRankCache(Long contestId) {
        if (contestId == null) {
            return;
        }

        String rankKey = getRankKey(contestId);
        Set<Object> userIds = redisTemplate.opsForZSet().range(rankKey, 0, -1);

        List<String> keys = new ArrayList<>();
        keys.add(rankKey);

        if (userIds != null) {
            for (Object userIdObj : userIds) {
                Long userId = parseLong(userIdObj, null);
                if (userId == null) {
                    continue;
                }

                String userKey = getUserKey(contestId, userId);
                keys.add(userKey);

                // SCAN 游标替代 KEYS：避免大 key 空间下阻塞 Redis 单线程
                Set<String> problemKeys = scanKeys(getProblemKeyPrefix(contestId, userId) + "*");
                if (problemKeys != null && !problemKeys.isEmpty()) {
                    keys.addAll(problemKeys);
                }
            }
        }

        if (!keys.isEmpty()) {
            redisTemplate.delete(new HashSet<>(keys));
        }
    }

    /**
     * SCAN 游标遍历匹配的 key（替代 KEYS 命令，防止阻塞 Redis 主线程）。
     *
     * @param pattern glob 匹配模式（如 {@code contest:rank:1:2:problem:*}）
     * @return 匹配到的全部 key；SCAN 不保证一次遍历返回全部，循环直至游标归零
     */
    private Set<String> scanKeys(String pattern) {
        Set<String> keys = new HashSet<>();
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(pattern).count(100).build())) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }
        return keys;
    }

    /**
     * 依据 user hash 组装榜单视图对象。
     * <p>
     * 显式把 {@code ratingBefore/ratingAfter/award} 置为 {@code null}、{@code isVisible} 置 1：
     * Redis 榜单不承载评分变化与奖项信息，这些字段要等赛后从 MySQL 的contest_rank 表补齐，
     * 这里先给中性默认值，避免序列化出 {@code null} 之外含义不明的 0。
     * </p>
     */
    private ContestRankVO buildRankVO(Long contestId, Long userId, int rankNum) {
        String userKey = getUserKey(contestId, userId);
        Map<Object, Object> userData = redisTemplate.opsForHash().entries(userKey);

        ContestRankVO vo = new ContestRankVO();
        vo.setContestId(contestId);
        vo.setUserId(userId);
        vo.setRankNum(rankNum);
        vo.setSolvedCount(parseInt(userData.get("solvedCount"), 0));
        vo.setPenaltyTime(parseInt(userData.get("penaltyTime"), 0));
        vo.setTotalScore(parseInt(userData.get("totalScore"), 0));
        vo.setRatingBefore(null);
        vo.setRatingAfter(null);
        vo.setAward(null);
        vo.setIsVisible(1);
        return vo;
    }

    /**
     * 首次参与时初始化 user hash 的计数字段。
     * <p>
     * 用 {@code hasKey} 先判存在、而不是用 HSETNX 逐字段判：字段是固定四个，
     * 一次 exists 往返优于四次 HSETNX；且这里是「用户级一次性初始化」，
     * 极端并发下即使两次初始化交错，写入的都是同一批初值，无副作用。
     * </p>
     */
    private void initUserIfAbsent(String userKey, Long userId) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(userKey))) {
            return;
        }
        redisTemplate.opsForHash().put(userKey, "userId", String.valueOf(userId));
        redisTemplate.opsForHash().put(userKey, "solvedCount", "0");
        redisTemplate.opsForHash().put(userKey, "penaltyTime", "0");
        redisTemplate.opsForHash().put(userKey, "totalScore", "0");
    }

    /**
     * 依据 user hash 中当前的解题数与罚时，重算并覆盖该用户的 ZSet 分数。
     * <p>
     * 用「从权威数据源重算」而非「对旧分做增量调整」：user hash 是唯一真相，
     * 重算天然幂等，即使上一次更新中途失败留了半截状态也能自愈。
     * </p>
     */
    private void refreshUserRankScore(Long contestId, Long userId) {
        String userKey = getUserKey(contestId, userId);
        int solvedCount = parseInt(redisTemplate.opsForHash().get(userKey, "solvedCount"), 0);
        int penaltyTime = parseInt(redisTemplate.opsForHash().get(userKey, "penaltyTime"), 0);

        String rankKey = getRankKey(contestId);
        long rankScore = buildRankScore(solvedCount, penaltyTime);
        redisTemplate.opsForZSet().add(rankKey, String.valueOf(userId), (double) rankScore);
    }

    /**
     * 把「解题数 + 罚时」二元排序编码为单一ZSet 分数，详见类注释「排序分编码」。
     * <p>
     * 用 long 计算再转 double：{@code solvedCount * SCORE_BASE} 在 int 运算下会溢出，
     * 必须保证左侧为 long 乘法；最终转 double 时值域远在精确整数范围内。
     * </p>
     */
    private long buildRankScore(int solvedCount, int penaltyTime) {
        return (long) solvedCount * SCORE_BASE - penaltyTime;
    }

    /**
     * 换算提交时间距开赛的分钟数，即罚时中的「时间分」。
     * <p>
     * 开赛时间为空时按0 计（而不是抛异常），开赛前提交则用 {@code Math.max} 兜到 0，
     * 防止因服务器时钟漂移或用户改本地时间导致的提交被算出负罚时、拉高名次。
     * </p>
     */
    private long calculateMinutesFromContestStart(Contest contest, LocalDateTime submitTime) {
        if (contest == null || contest.getStartTime() == null) {
            return 0L;
        }
        long minutes = Duration.between(contest.getStartTime(), submitTime).toMinutes();
        return Math.max(minutes, 0L);
    }

    /**
     * 续期三把 key 的过期时间。
     * <p>
     * 每次提交都续期，而非只在首次创建时设置：榜单的生命周期应当跟随最后一场比赛的使用，
     * 而非开赛那一刻起固定倒数 7 天。设为相对时间即可，无需额外记录创建时刻。
     * </p>
     */
    private void expireContestKeys(Long contestId, Long userId, Long problemId) {
        String rankKey = getRankKey(contestId);
        String userKey = getUserKey(contestId, userId);
        String problemKey = getProblemKey(contestId, userId, problemId);

        redisTemplate.expire(rankKey, CACHE_EXPIRE_DAYS, TimeUnit.DAYS);
        redisTemplate.expire(userKey, CACHE_EXPIRE_DAYS, TimeUnit.DAYS);
        redisTemplate.expire(problemKey, CACHE_EXPIRE_DAYS, TimeUnit.DAYS);
    }

    private String getRankKey(Long contestId) {
        return KEY_PREFIX + contestId;
    }

    private String getUserKey(Long contestId, Long userId) {
        return KEY_PREFIX + contestId + ":user:" + userId;
    }

    private String getProblemKey(Long contestId, Long userId, Long problemId) {
        return KEY_PREFIX + contestId + ":user:" + userId + ":problem:" + problemId;
    }

    private String getProblemKeyPrefix(Long contestId, Long userId) {
        return KEY_PREFIX + contestId + ":user:" + userId + ":problem:";
    }

    private int parseInt(Object value, int defaultValue) {
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private Long parseLong(Object value, Long defaultValue) {
        try {
            return value == null ? defaultValue : Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
