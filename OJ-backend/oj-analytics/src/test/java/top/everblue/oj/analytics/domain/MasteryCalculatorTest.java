package top.everblue.oj.analytics.domain;

import org.junit.jupiter.api.Test;
import top.everblue.oj.api.profile.TagStat;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 掌握度公式的已知值测试（seam：MasteryCalculator.mastery 纯函数）。
 */
class MasteryCalculatorTest {

    private static TagStat stat(int attempted, int passed, int firstTryPassed,
                                int totalSubmissions, int maxDifficulty) {
        return new TagStat(
                1L, "dp", null, "ALGORITHM",
                attempted, passed, firstTryPassed, totalSubmissions, 0.0, maxDifficulty
        );
    }

    @Test
    void zeroAttemptsIsZero() {
        assertEquals(0, MasteryCalculator.mastery(stat(0, 0, 0, 0, 0)));
    }

    @Test
    void perfectRecordScoresOneHundred() {
        // 全 AC + 一次通过 + 打到最高难度 → 0.4 + 0.3 + 0.2 + 0.1 = 1.0
        assertEquals(100, MasteryCalculator.mastery(stat(3, 3, 3, 3, 3000)));
    }

    @Test
    void activeButAllFailedScoresTen() {
        // 有提交但全错：只拿到 recency 权重 0.1
        assertEquals(10, MasteryCalculator.mastery(stat(5, 0, 0, 5, 800)));
    }

    @Test
    void mixedRecordMatchesHandComputedValue() {
        // passRate=0.5(0.20) + diffCov=(2200-800)/2200≈0.6364(0.1909)
        // + firstTry=0.2(0.04) + recency(0.1) → 53.09 → 53
        assertEquals(53, MasteryCalculator.mastery(stat(10, 5, 2, 12, 2200)));
    }

    @Test
    void difficultyBelowMinimumContributesNothing() {
        // diffCov=0：0.4*0.5 + 0 + 0.2*0.5 + 0.1 = 0.4 → 40
        assertEquals(40, MasteryCalculator.mastery(stat(2, 1, 1, 2, 500)));
    }

    @Test
    void difficultyAboveMaximumIsCappedAtOne() {
        assertEquals(100, MasteryCalculator.mastery(stat(2, 2, 2, 2, 4000)));
    }
}
