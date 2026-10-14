package top.everblue.oj.problem.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** GESP 等第预估（seam：GespLevelEstimator 纯函数）。 */
class GespLevelEstimatorTest {

    @Test
    void estimateUsesHighestReachedLevel() {
        Map<Integer, Integer> thresholds = GespLevelEstimator.defaultThresholds();
        assertEquals(0, GespLevelEstimator.estimate(0, thresholds));
        assertEquals(0, GespLevelEstimator.estimate(39, thresholds));
        assertEquals(1, GespLevelEstimator.estimate(40, thresholds));
        assertEquals(2, GespLevelEstimator.estimate(50, thresholds));
        assertEquals(5, GespLevelEstimator.estimate(72, thresholds));
        assertEquals(8, GespLevelEstimator.estimate(90, thresholds));
        assertEquals(8, GespLevelEstimator.estimate(100, thresholds));
    }

    @Test
    void thresholdsFromConfigParsesAndFallsBack() {
        Map<Integer, Integer> custom = GespLevelEstimator.thresholdsFromConfig("1:30;2:60;3:80");
        assertEquals(0, GespLevelEstimator.estimate(29, custom));
        assertEquals(1, GespLevelEstimator.estimate(30, custom));
        assertEquals(2, GespLevelEstimator.estimate(60, custom));
        assertEquals(3, GespLevelEstimator.estimate(85, custom));
        assertEquals(GespLevelEstimator.defaultThresholds(),
                GespLevelEstimator.thresholdsFromConfig(null));
        assertEquals(GespLevelEstimator.defaultThresholds(),
                GespLevelEstimator.thresholdsFromConfig("垃圾输入"));
    }
}
