package top.everblue.oj.problem.domain;

import org.junit.jupiter.api.Test;
import top.everblue.oj.problem.entity.ExamQuestion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 客观题判分（seam：ObjectiveJudge.isCorrect 纯函数）。 */
class ObjectiveJudgeTest {

    private ExamQuestion question(String type, String answer) {
        ExamQuestion q = new ExamQuestion();
        q.setQuestionType(type);
        q.setAnswer(answer);
        return q;
    }

    @Test
    void singleChoiceIgnoresCaseAndWhitespace() {
        assertTrue(ObjectiveJudge.isCorrect(question("SINGLE", "B"), " b "));
        assertFalse(ObjectiveJudge.isCorrect(question("SINGLE", "B"), "C"));
    }

    @Test
    void multipleChoiceComparesAsSet() {
        assertTrue(ObjectiveJudge.isCorrect(question("MULTIPLE", "ABD"), "dba"));
        assertTrue(ObjectiveJudge.isCorrect(question("MULTIPLE", "AC"), "AC"));
        assertFalse(ObjectiveJudge.isCorrect(question("MULTIPLE", "ABD"), "AB")); // 少选
        assertFalse(ObjectiveJudge.isCorrect(question("MULTIPLE", "ABD"), "ABCD")); // 多选
    }

    @Test
    void judgeAcceptsChineseAndSymbolVariants() {
        assertTrue(ObjectiveJudge.isCorrect(question("JUDGE", "T"), "对"));
        assertTrue(ObjectiveJudge.isCorrect(question("JUDGE", "F"), "×"));
        assertTrue(ObjectiveJudge.isCorrect(question("JUDGE", "T"), "true"));
        assertFalse(ObjectiveJudge.isCorrect(question("JUDGE", "T"), "F"));
    }

    @Test
    void emptyOrIllegalAnswerNeverMatches() {
        assertFalse(ObjectiveJudge.isCorrect(question("SINGLE", "A"), ""));
        assertFalse(ObjectiveJudge.isCorrect(question("SINGLE", "A"), "1"));
        assertFalse(ObjectiveJudge.isCorrect(question("JUDGE", "T"), "也许"));
    }
}
