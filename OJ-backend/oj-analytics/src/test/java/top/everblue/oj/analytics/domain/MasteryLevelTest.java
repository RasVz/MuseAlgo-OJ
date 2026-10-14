package top.everblue.oj.analytics.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 掌握度 → 话术分档的边界测试（seam：MasteryLevel.of）。
 */
class MasteryLevelTest {

    @Test
    void boundariesMapToExpectedBands() {
        assertEquals(MasteryLevel.EXCELLENT, MasteryLevel.of(100));
        assertEquals(MasteryLevel.EXCELLENT, MasteryLevel.of(85));
        assertEquals(MasteryLevel.GOOD, MasteryLevel.of(84));
        assertEquals(MasteryLevel.GOOD, MasteryLevel.of(70));
        assertEquals(MasteryLevel.ADEQUATE, MasteryLevel.of(69));
        assertEquals(MasteryLevel.ADEQUATE, MasteryLevel.of(55));
        assertEquals(MasteryLevel.WEAK, MasteryLevel.of(54));
        assertEquals(MasteryLevel.WEAK, MasteryLevel.of(0));
    }

    @Test
    void labelsAreParentFacing() {
        assertEquals("优秀", MasteryLevel.EXCELLENT.getLabel());
        assertEquals("良好", MasteryLevel.GOOD.getLabel());
        assertEquals("及格", MasteryLevel.ADEQUATE.getLabel());
        assertEquals("薄弱", MasteryLevel.WEAK.getLabel());
    }
}
