package top.everblue.oj.ai.hint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hint Ladder 分级提示规则（seam：HintLadderGuide.guideFor 纯函数）。 */
class HintLadderGuideTest {

    @Test
    void everyLevelHasDistinctRules() {
        String l1 = HintLadderGuide.guideFor(1);
        String l2 = HintLadderGuide.guideFor(2);
        String l3 = HintLadderGuide.guideFor(3);
        String l4 = HintLadderGuide.guideFor(4);

        assertTrue(l1.contains("L1 思路方向"));
        assertTrue(l2.contains("L2 关键观察"));
        assertTrue(l3.contains("L3 伪代码"));
        assertTrue(l4.contains("L4 完整题解"));
        assertFalse(l1.equals(l2));
        assertFalse(l2.equals(l3));
        assertFalse(l3.equals(l4));
    }

    @Test
    void higherLevelsGrantMoreConcreteness() {
        String l3 = HintLadderGuide.guideFor(3);
        String l4 = HintLadderGuide.guideFor(4);
        // L3 明确禁止完整代码，L4 明确要求可提交 AC 代码
        assertTrue(l3.contains("不给出完整可提交"));
        assertTrue(l4.contains("完整 AC 代码"));
    }

    @Test
    void invalidLevelMeansDefaultMode() {
        assertEquals("", HintLadderGuide.guideFor(0));
        assertEquals("", HintLadderGuide.guideFor(5));
        assertEquals("", HintLadderGuide.guideFor(-1));
    }
}
