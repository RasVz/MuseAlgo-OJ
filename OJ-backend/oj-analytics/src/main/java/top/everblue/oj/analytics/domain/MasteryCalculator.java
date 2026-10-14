package top.everblue.oj.analytics.domain;

import top.everblue.oj.api.profile.TagStat;

import java.time.temporal.ChronoUnit;

/**
 * 能力掌握度计算器——纯静态工具，不依赖 Spring / DB，便于单元测试。
 * <p>
 * 掌握度公式（0-100）：
 * <pre>
 *   mastery = 100 × (
 *       0.40 × passRate          // 通过率
 *     + 0.30 × difficultyCoverage // 高难度覆盖
 *     + 0.20 × firstTryRate      // 一次通过率
 *     + 0.10 × recencyScore      // 近期活跃（暂留权重，待有周快照数据后启用）
 *   )
 * </pre>
 * 权重可随时调整而不影响 SQL 层。
 */
public final class MasteryCalculator {

    private MasteryCalculator() {} // 工具类

    static final double W_PASS_RATE = 0.40;
    static final double W_DIFFICULTY = 0.30;
    static final double W_FIRST_TRY = 0.20;
    static final double W_RECENCY = 0.10;

    // 难度范围：800（简单）~ 3000（极难）
    private static final int MIN_DIFFICULTY = 800;
    private static final int MAX_DIFFICULTY = 3000;

    /**
     * 计算单个 tag 的掌握度（0-100）。
     *
     * @param stat 原始统计，由 SQL 聚合
     * @return 0-100 整数分
     */
    public static int mastery(TagStat stat) {
        if (stat.attempted() == 0) return 0;

        double passRate = (double) stat.passed() / stat.attempted();
        double diffCov = difficultyCoverage(stat.maxDifficulty());
        double firstTry = (double) stat.firstTryPassed() / stat.attempted();
        double recency = 1.0;

        double score = 100 * (W_PASS_RATE * passRate
                + W_DIFFICULTY * diffCov
                + W_FIRST_TRY * firstTry
                + W_RECENCY * recency);

        return (int) Math.round(Math.min(score, 100));
    }

    /**
     * 难度覆盖率：在 800-3000 区间归一化。
     */
    static double difficultyCoverage(int maxDifficulty) {
        if (maxDifficulty < MIN_DIFFICULTY) return 0;
        return Math.min(1.0, (double) (maxDifficulty - MIN_DIFFICULTY)
                / (MAX_DIFFICULTY - MIN_DIFFICULTY));
    }
}