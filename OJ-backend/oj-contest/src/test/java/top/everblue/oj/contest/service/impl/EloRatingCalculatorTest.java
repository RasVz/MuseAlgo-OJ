package top.everblue.oj.contest.service.impl;

import org.junit.jupiter.api.Test;
import top.everblue.oj.contest.entity.UserRating;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 比赛 Rating 计算的不变式测试（seam：RatingCalculator.calculate）。
 * 只断言文档化契约，不锁定内部二分搜索的中间值：
 * 单人不变 / delta 上限 ±400 / 最低 200 / 排名与增减方向一致。
 */
class EloRatingCalculatorTest {

    private final EloRatingCalculator calculator = new EloRatingCalculator();

    @Test
    void singleParticipantKeepsRating() {
        List<UserRating> result = calculator.calculate(1L, List.of(7L), List.of(1500));
        assertEquals(1, result.size());
        assertEquals(1500, result.get(0).getRating());
        assertEquals(0, result.get(0).getRatingChange());
        assertEquals(1, result.get(0).getRank());
    }

    @Test
    void twoEqualParticipantsSymmetricWithinCap() {
        List<UserRating> result = calculator.calculate(1L, List.of(1L, 2L), List.of(1200, 1200));
        UserRating winner = result.get(0);
        UserRating loser = result.get(1);

        // 冠军 +400（触顶），亚军 −400：分差守恒
        assertEquals(400, winner.getRatingChange());
        assertEquals(-400, loser.getRatingChange());
        assertEquals(1600, winner.getRating());
        assertEquals(800, loser.getRating());
        assertEquals(2400, winner.getRating() + loser.getRating());
    }

    @Test
    void rankingsMatchDeltaDirectionWithCapAndFloor() {
        List<UserRating> result = calculator.calculate(1L, List.of(1L, 2L, 3L),
                List.of(1500, 1500, 1500));

        UserRating first = result.get(0);
        UserRating second = result.get(1);
        UserRating third = result.get(2);

        // 方向一致：冠军 ≥ 亚军 ≥ 季军（增减方向与排名单调）
        assertTrue(first.getRatingChange() >= second.getRatingChange());
        assertTrue(second.getRatingChange() >= third.getRatingChange());
        // delta 上限与底线
        for (UserRating r : result) {
            assertTrue(r.getRatingChange() <= 400);
            assertTrue(r.getRatingChange() >= -400);
            assertTrue(r.getRating() >= 200);
        }
    }

    @Test
    void bigUpsetDeltaIsCapped() {
        // 800 分选手击败 2000 分选手：涨幅不得超过 400
        List<UserRating> result = calculator.calculate(1L, List.of(9L, 8L), List.of(800, 2000));
        UserRating winner = result.get(0);
        assertTrue(winner.getRatingChange() <= 400);
        assertTrue(winner.getRating() >= 800);
        assertTrue(result.get(1).getRating() <= 2000);
    }
}
