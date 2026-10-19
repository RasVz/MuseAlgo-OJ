package top.everblue.oj.contest.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.everblue.oj.contest.entity.UserRating;
import top.everblue.oj.contest.service.RatingCalculator;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Elo 变体 Rating 计算实现。
 *
 * <h3>算法步骤</h3>
 * <ol>
 *   <li>对每个选手 i，计算与其他选手 j 的预期胜率：
 *       {@code E_ij = 1 / (1 + 10^((R_j - R_i) / 400))}</li>
 *   <li>seed_i = 1 + Σ E_ij（预期排名，1-based）</li>
 *   <li>几何平均 rating = 所有选手 rating 的加权平均</li>
 *   <li>表现分：二分查找使 seed ≈ actual_rank 的 rating 值</li>
 *   <li>新 rating = (旧 rating + 表现分) / 2</li>
 *   <li>delta 上限：|delta| ≤ 400，避免单场波动过大</li>
 * </ol>
 *
 * <h3>参考</h3>
 * Codeforces Rating System (Mike Mirzayanov, 2010)
 */
@Slf4j
@Service
public class EloRatingCalculator implements RatingCalculator {

    /**
     * 平滑系数：{@code new = (old + performance) * K_FACTOR}。
     * 取 0.5 表示新分向表现分靠拢一半，单场影响减半——
     * 系数越接近 1 分数越贴近真实水平，但一场比赛的偶然性也会被放大得越厉害。
     */
    private static final double K_FACTOR = 0.5;

    /**
     * 单场涨跌幅上限。用于兜住「炸鱼场」：低分段选手碰上高分段选手全胜，
     * 公式算出的 delta 可能上千，不设限会让分差在几场之内失真。
     */
    private static final int MAX_DELTA = 400;

    /** 评分下限。新用户从较低起点起步，且防止分母类运算出现非正数。 */
    private static final int MIN_RATING = 200;

    /**
     * 计算一场比赛所有参赛者的 rating 变化。
     * <p>
     * 算法整体是 O(n²)：seed 计算与二分查找都要遍历所有对手。
     * 参赛人数在数百量级时完全可接受，<b>不需要</b>改写成增量更新或缓存。
     * </p>
     *
     * @param contestId      比赛 ID
     * @param userIds        参赛者 ID 列表（按排名升序，第 1 名为冠军）
     * @param currentRatings 参赛者当前 rating（与 userIds 同序）
     * @return 每个参赛者的 rating 记录（含 delta）
     */
    @Override
    public List<UserRating> calculate(Long contestId, List<Long> userIds, List<Integer> currentRatings) {
        return calculate(contestId,userIds,currentRatings,java.util.stream.IntStream.rangeClosed(1,userIds.size()).boxed().toList());
    }

    @Override
    public List<UserRating> calculate(Long contestId,List<Long> userIds,List<Integer> currentRatings,List<Integer> ranks) {
        int n = userIds.size();
        if (n <= 1) {
            // 单人比赛不计算 rating（或者给 +0）
            // 只有一个人时「相对表现」无从谈起，所有预期胜率之和恒为 0，公式退化，
            // 与其算出无意义的分不如记 0 delta 保持分数不变。
            return handleSmallContest(contestId, userIds, currentRatings);
        }

        // ---- 第 1 步：计算每个人的 seed（预期排名） ----
// seed 表示「按赛前分数，这人本该排第几」，实际排名与之的差距即为本场的表现分依据。
// 用两重循环而非树状结构（按分数排序后二分累计和）：n 为数百级，O(n²) 远快于维护平衡树的常数开销。
        double[] seeds = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                sum += winProbability(currentRatings.get(j), currentRatings.get(i));
            }
            seeds[i] = 1 + sum; // seed = 1 + 对所有人的预期胜率之和
        }

        // ---- 第 2 步：计算几何平均 rating ----
// 几何平均而非算术平均：rating 是对数尺度的（Elo 公式里的 400 与 10 的幂都说明了这点），
// 算术平均会被少数高分段选手拉偏，几何平均才与公式的尺度一致。
        double logSum = 0;
        for (int r : currentRatings) {
            // Math.max(r, 1) 兜底：rating 若为 0 或负数，log10 会得到 -Inf/NaN 污染整场计算
            logSum += Math.log10(Math.max(r, 1));
        }
        double geoMeanRating = Math.pow(10, logSum / n);

        // ---- 第 3 步：对每个人计算表现分 → 新 rating ----
        List<UserRating> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int groupEnd=i;
            while(groupEnd+1<n && ranks.get(groupEnd+1).equals(ranks.get(i))) groupEnd++;
            double actualRank=(ranks.get(i)+groupEnd+1)/2.0; // 并列组使用平均位置，避免按用户ID偏置评分
            double seed = seeds[i];
            int oldRating = currentRatings.get(i);

            // 二分搜索表现分：找到一个 hypothetical rating R，使得 seed(R) ≈ actualRank
            double performanceRating = findPerformanceRating(
                    currentRatings, i, seed, actualRank, geoMeanRating);

            // 平滑：新 rating = (旧 + 表现分) / 2
            int newRating = (int) Math.round((oldRating + performanceRating) * K_FACTOR);

            // delta 上限：先算 delta 再截断，并同步修正 newRating，
            // 保证「rating - oldRating == ratingChange」这条等式在落库后依然成立（见下方 MIN_RATING 处的例外）。
            int delta = newRating - oldRating;
            if (delta > MAX_DELTA) {
                delta = MAX_DELTA;
                newRating = oldRating + MAX_DELTA;
            } else if (delta < -MAX_DELTA) {
                delta = -MAX_DELTA;
                newRating = oldRating - MAX_DELTA;
            }

            // 触底后按实际新旧分差记录流水，保持当前分与变更记录一致。
            if (newRating < MIN_RATING) newRating = MIN_RATING;

            UserRating record = new UserRating();
            record.setUserId(userIds.get(i));
            record.setContestId(contestId);
            record.setRating(newRating);
            record.setRatingChange(newRating-oldRating);
            record.setRank(ranks.get(i));
            record.setCreateTime(LocalDateTime.now());
            results.add(record);

            log.debug("Rating: userId={}, old={}, perf={}, new={}, delta={}, rank={}",
                    userIds.get(i), oldRating, (int) performanceRating, newRating, delta, actualRank);
        }

        return results;
    }

    // ======================== 算法核心 ========================

    /**
     * 计算 R_B 对 R_A 的预期胜率（Elo 公式）。
     * E = 1 / (1 + 10^((R_B - R_A) / 400))
     * <p>
     * 400 是 Elo 的标准常数：分差 400 时预期胜率约为 76%，与真实竞技水平大致对应。
     * 它同时决定了分数的「有效刻度」——不要随意调大，分差会被压平导致评分失去区分度。
     * </p>
     */
    private double winProbability(int ratingB, int ratingA) {
        return 1.0 / (1.0 + Math.pow(10, (ratingB - ratingA) / 400.0));
    }

    /**
     * 二分搜索表现分。
     * 找到一个 rating 值 R，使得在该 rating 下，选手 i 的预期排名 ≈ actualRank。
     * <p>
     * 固定 50 轮而非「直到区间收敛」：搜索区间宽度约 2000，每轮减半，
     * 50 轮后区间已远小于 1 分，精度早已超过整数评分的分辨率，
     * 再迭代只是白耗 CPU。同时固定轮数也让结果<b>可复现</b>，不受浮点收敛边界影响。
     * </p>
     *
     * @param ratings    全体参赛者赛前评分
     * @param excludeIdx 本人在 {@code ratings} 中的下标，需从对手统计中排除
     * @param currentSeed 本人当前的预期排名（未参与计算，保留以对应公式语义）
     * @param actualRank 实际排名（1-based）
     * @param geoMean    参赛者评分的几何平均，用作二分区间的中心
     * @return 表现分（double，二分区间中点）
     */
    private double findPerformanceRating(List<Integer> ratings, int excludeIdx,
                                         double currentSeed, double actualRank, double geoMean) {
        // 种子范围：在几何平均周围搜索。±1000 覆盖了 elo 体系下所有现实分差，
        // 区间外的选手（远超或远低于全场水平）其表现分必然落在这 ±1000 之内。
        double lo = Math.max(geoMean - 1000, MIN_RATING);
        double hi = geoMean + 1000;

        for (int iter = 0; iter < 50; iter++) {
            double mid = (lo + hi) / 2;
            // 计算如果 rating = mid，预期排名是多少
            // 与第 1 步 seed 保持一致的 1-based 期望排名：
            // expectedRank = 1 + Σ P(其他选手胜过 mid 评分者)。
            // 此前用 0.5 作基准，与 seed 的 1 + Σ 不一致，导致二分搜索整体偏向
            // 搜索下限，冠军表现分被系统性低估（已由 EloRatingCalculatorTest 覆盖）。
            double expectedRank = 1.0;
            for (int j = 0; j < ratings.size(); j++) {
                if (j == excludeIdx) continue;
                expectedRank += winProbability(ratings.get(j), (int) mid);
            }
            if (expectedRank > actualRank) {
                lo = mid; // 预期排名太高 → 表现分太低 → 提高
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }

    /**
     * 参赛人数不足 2 时的退化处理：只落一条 delta=0 的流水记录，名次照常记。
     * <p>
     * 仍然要写这条记录而不是跳过：评分曲线是按比赛取点的，
     * 漏掉会让折线在该场比赛处凭空直连，看不出「参加了但无变化」。
     * </p>
     *
     * @return 与入参等长、delta 全为 0 的流水列表
     */
    private List<UserRating> handleSmallContest(Long contestId, List<Long> userIds,
                                                 List<Integer> ratings) {
        List<UserRating> results = new ArrayList<>();
        for (int i = 0; i < userIds.size(); i++) {
            UserRating r = new UserRating();
            r.setUserId(userIds.get(i));
            r.setContestId(contestId);
            r.setRating(ratings.get(i));
            r.setRatingChange(0);
            r.setRank(i + 1);
            r.setCreateTime(LocalDateTime.now());
            results.add(r);
        }
        return results;
    }
}
