package com.musealgo.oj.ai.draft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析器是整条生成链路唯一把「模型文本」变成「可审阅内容」的地方，
 * 这里覆盖的都是实际见过的畸形输出，而不是抽象边界。
 */
class DraftStatementParserTest {

    private final DraftBrief brief = DraftBrief.defaults();

    @Test
    void parsesFencedJsonWithSurroundingProse() {
        String raw = """
                好的，下面是题目：
                ```json
                {"title":"知识星排队","content":"小慕在排队。","inputDesc":"第一行 n","outputDesc":"输出答案",
                 "hint":"","samples":[{"input":"3","output":"6","explanation":"3+3"}],
                 "tags":["模拟"],"difficultyScore":1200,"timeLimit":1000,"memoryLimit":256}
                ```
                """;
        DraftContent content = DraftStatementParser.parse(raw, brief);
        assertEquals("知识星排队", content.title());
        assertEquals(1, content.samples().size());
        assertEquals("6", content.samples().get(0).output());
        assertEquals(256, content.memoryLimit());
    }

    @Test
    void skipsNonJsonBraceBlockBeforeRealObject() {
        String raw = "格式说明 {不是 JSON} 正文如下 {\"title\":\"T\",\"content\":\"C\"}";
        DraftContent content = DraftStatementParser.parse(raw, brief);
        assertEquals("T", content.title());
        assertEquals("C", content.content());
    }

    @Test
    void acceptsSingleQuotedObject() {
        DraftContent content = DraftStatementParser.parse("{'title':'T','content':'C'}", brief);
        assertEquals("T", content.title());
    }

    @Test
    void truncatedJsonRaisesReadableFailure() {
        DraftStatementParser.DraftParseException failure = assertThrows(
                DraftStatementParser.DraftParseException.class,
                () -> DraftStatementParser.parse("{\"title\":\"T\",\"content\":\"C\"", brief));
        assertTrue(failure.getMessage().contains("无法解析") || failure.getMessage().contains("不是 JSON"));
    }

    @Test
    void emptyOutputRaisesFailure() {
        assertThrows(DraftStatementParser.DraftParseException.class,
                () -> DraftStatementParser.parse("   ", brief));
    }

    @Test
    void tagsAcceptCommaStringAndDeduplicate() {
        DraftContent content = DraftStatementParser.parse(
                "{\"title\":\"T\",\"content\":\"C\",\"tags\":\"#贪心, 排序，贪心\"}", brief);
        assertEquals(2, content.tags().size());
        assertEquals("贪心", content.tags().get(0));
        assertEquals("排序", content.tags().get(1));
    }

    @Test
    void samplesAcceptPairArrays() {
        DraftContent content = DraftStatementParser.parse(
                "{\"title\":\"T\",\"content\":\"C\",\"samples\":[[\"1 2\",\"3\"],{\"input\":\"2\",\"output\":\"4\"}]}",
                brief);
        assertEquals(2, content.samples().size());
        assertEquals("3", content.samples().get(0).output());
        assertEquals("4", content.samples().get(1).output());
    }

    @Test
    void missingDifficultyFallsBackToBandLowerBound() {
        DraftContent content = DraftStatementParser.parse("{\"title\":\"T\",\"content\":\"C\"}", brief);
        assertEquals(1200, content.difficultyScore());
    }

    @Test
    void difficultyOutsideGlobalRangeIsClamped() {
        DraftContent content = DraftStatementParser.parse(
                "{\"title\":\"T\",\"content\":\"C\",\"difficultyScore\":99}", brief);
        assertEquals(800, content.difficultyScore());
    }

    @Test
    void secondsTimeLimitIsConvertedToMillis() {
        DraftContent content = DraftStatementParser.parse(
                "{\"title\":\"T\",\"content\":\"C\",\"timeLimit\":\"1s\"}", brief);
        assertEquals(1000, content.timeLimit());
    }

    @Test
    void decimalDurationIsNotConcatenatedIntoDigits() {
        DraftContent content = DraftStatementParser.parse(
                "{\"title\":\"T\",\"content\":\"C\",\"timeLimit\":1.5}", brief);
        assertEquals(1500, content.timeLimit());
    }

    @Test
    void longTitleIsTruncatedAtCodePointBoundary() {
        String title = "题".repeat(200);
        DraftContent content = DraftStatementParser.parse(
                "{\"title\":\"" + title + "\",\"content\":\"C\"}", brief);
        assertEquals(128, content.title().codePointCount(0, content.title().length()));
    }

    @Test
    void descriptionFallsBackToAlternativeKeys() {
        DraftContent content = DraftStatementParser.parse(
                "{\"name\":\"T\",\"description\":\"C\",\"inputFormat\":\"in\",\"outputFormat\":\"out\"}", brief);
        assertEquals("T", content.title());
        assertEquals("C", content.content());
        assertEquals("in", content.inputDesc());
        assertEquals("out", content.outputDesc());
    }
}
