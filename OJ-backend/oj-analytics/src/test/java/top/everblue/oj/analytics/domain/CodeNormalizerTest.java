package top.everblue.oj.analytics.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归一化器单测：模板头剥离 / 注释剥离 / 标识符统一 / 空输入。
 */
class CodeNormalizerTest {

    @Test
    void stripsTemplateHeader() {
        String code = """
                #include <bits/stdc++.h>
                #define int long long
                using namespace std;
                int main() {
                    return 0;
                }
                """;
        List<String> tokens = CodeNormalizer.normalize(code);
        // 预处理行/using 行全部剥离，剩余应为 main 函数体
        assertFalse(tokens.contains("#"));
        assertFalse(tokens.contains("include"));
        assertFalse(tokens.contains("using"));
        assertFalse(tokens.contains("namespace"));
        assertFalse(tokens.contains("define"));
        assertEquals("int", tokens.get(0));
        assertTrue(tokens.contains("return"));
    }

    @Test
    void stripsLineAndBlockComments() {
        String code = """
                int a = 1; // 行注释
                /* 块注释
                   跨行内容 */
                int b = 2;
                """;
        List<String> tokens = CodeNormalizer.normalize(code);
        assertFalse(tokens.contains("行注释"));
        assertFalse(tokens.contains("块注释"));
        assertFalse(tokens.contains("跨行内容"));
        assertEquals(List.of("int", "ID", "=", "NUM", ";", "int", "ID", "=", "NUM", ";"), tokens);
    }

    @Test
    void unifiesIdentifiersAndNumbersButKeepsKeywords() {
        String code = "int apple = 1; int banana = 2; for (int i = 0; i < 10; i++) {}";
        List<String> tokens = CodeNormalizer.normalize(code);
        assertFalse(tokens.contains("apple"));
        assertFalse(tokens.contains("banana"));
        assertEquals("int", tokens.get(0));
        assertTrue(tokens.contains("for")); // 关键字保留
        assertEquals(CodeNormalizer.NUMBER_TOKEN, tokens.get(3));
        assertEquals(CodeNormalizer.IDENTIFIER_TOKEN, tokens.get(1));
        // 标识符（apple/banana/循环 i）全部统一为同一占位符：共 5 处
        assertEquals(5, tokens.stream().filter(CodeNormalizer.IDENTIFIER_TOKEN::equals).count());
    }

    @Test
    void stringLiteralSlashNotMistakenAsComment() {
        // 字符串内的 "//" 不应触发注释剥离，行尾真实注释应剥离
        String code = """
                cout << "a//b";
                int x = 1; // real comment
                """;
        List<String> tokens = CodeNormalizer.normalize(code);
        assertFalse(tokens.contains("real"));
        assertFalse(tokens.contains("comment"));
        assertTrue(tokens.contains(CodeNormalizer.STRING_TOKEN));
    }

    @Test
    void emptyOrBlankInputYieldsEmptyTokens() {
        assertTrue(CodeNormalizer.normalize(null).isEmpty());
        assertTrue(CodeNormalizer.normalize("").isEmpty());
        assertTrue(CodeNormalizer.normalize("   \n  \n").isEmpty());
    }
}
