package top.everblue.oj.contest.service;

import top.everblue.oj.contest.entity.UserRating;

import java.util.List;

/**
 * 比赛 Rating 计算服务（Elo 变体，类似 Codeforces）。
 *
 * <h3>算法概要</h3>
 * <ol>
 *   <li>取所有参赛者的当前 rating</li>
 *   <li>对每个选手：根据 rating 差计算与其他人的预期胜率 → 求和得到 seed（预期排名）</li>
 *   <li>根据实际排名反推 performance rating（表现分）</li>
 *   <li>新 rating = (旧 rating + 表现分) / 2（平滑处理）</li>
 *   <li>delta = 新 rating - 旧 rating</li>
 * </ol>
 */
public interface RatingCalculator {

    /**
     * 计算一场比赛所有参赛者的 rating 变化。
     *
     * @param contestId      比赛 ID
     * @param userIds        参赛者 ID 列表（按排名升序，第 1 名为冠军）
     * @param currentRatings 参赛者当前 rating（与 userIds 同序）
     * @return 每个参赛者的 rating 记录（含 delta）
     */
    List<UserRating> calculate(Long contestId, List<Long> userIds, List<Integer> currentRatings);

    default List<UserRating> calculate(Long contestId, List<Long> userIds, List<Integer> currentRatings, List<Integer> ranks) {
        return calculate(contestId,userIds,currentRatings);
    }
}
