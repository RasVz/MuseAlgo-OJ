package top.everblue.oj.analytics.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * C++ 代码归一化器（查重前处理）。
 * <p>
 * M1 规则（方案第 7 节）：
 * <ul>
 *   <li>剥离模板头：以 {@code #} 开头的预处理行（include/define）、{@code using namespace} 行</li>
 *   <li>去注释：{@code //} 行注释与 {@code /* *}{@code /} 块注释（状态机识别，字符串字面量内的注释符号不误伤）</li>
 *   <li>字符串/字符字面量统一为占位符，避免输出文案差异干扰结构比对</li>
 *   <li>统一标识符：非关键字标识符 {@code [a-zA-Z_]\w*} → {@code ID}，数字 → {@code NUM}（关键字保留，保留控制流结构）</li>
 *   <li>去空白：输出词法单元序列（token），由指纹器做 shingle</li>
 * </ul>
 * </p>
 */
public final class CodeNormalizer {

    /** 非关键字标识符统一占位符 */
    public static final String IDENTIFIER_TOKEN = "ID";
    /** 数字字面量统一占位符 */
    public static final String NUMBER_TOKEN = "NUM";
    /** 字符串/字符字面量统一占位符 */
    public static final String STRING_TOKEN = "STR";

    /** C++ 关键字（保留，保持控制流/类型结构可区分） */
    private static final Set<String> CPP_KEYWORDS = Set.of(
            "alignas", "alignof", "asm", "auto", "bool", "break", "case", "catch", "char",
            "char16_t", "char32_t", "class", "const", "constexpr", "const_cast", "continue",
            "decltype", "default", "delete", "do", "double", "dynamic_cast", "else", "enum",
            "explicit", "export", "extern", "false", "float", "for", "friend", "goto", "if",
            "inline", "int", "long", "mutable", "namespace", "new", "noexcept", "nullptr",
            "operator", "private", "protected", "public", "register", "reinterpret_cast",
            "return", "short", "signed", "sizeof", "static", "static_assert", "static_cast",
            "struct", "switch", "template", "this", "thread_local", "throw", "true", "try",
            "typedef", "typeid", "typename", "union", "unsigned", "using", "virtual", "void",
            "volatile", "wchar_t", "while");

    private CodeNormalizer() {
    }

    /**
     * 归一化代码为词法单元序列。
     *
     * @param code 原始代码
     * @return token 列表（空/空白输入返回空列表）
     */
    public static List<String> normalize(String code) {
        if (code == null || code.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        boolean inBlockComment = false;
        for (String line : code.split("\n", -1)) {
            String stripped = line.strip();
            // 模板头/预处理行整行剥离（M1：按行前缀处理，接受小误报）
            if (stripped.isEmpty() || stripped.startsWith("#")
                    || stripped.startsWith("using namespace")) {
                continue;
            }
            inBlockComment = scanLine(line, inBlockComment, tokens);
        }
        return tokens;
    }

    /**
     * 单行扫描：状态机去注释、字符串保护、切词。
     *
     * @return 扫描结束后是否仍处于块注释中（跨行状态）
     */
    private static boolean scanLine(String line, boolean inBlockComment, List<String> tokens) {
        StringBuilder word = new StringBuilder();
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';

            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inLineComment) {
                break;
            }
            if (inString) {
                if (c == '\\') {
                    i++; // 跳过转义
                } else if (c == '"') {
                    inString = false;
                    tokens.add(STRING_TOKEN);
                }
                continue;
            }
            if (inChar) {
                if (c == '\\') {
                    i++;
                } else if (c == '\'') {
                    inChar = false;
                    tokens.add(STRING_TOKEN);
                }
                continue;
            }

            // 注释开始
            if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            // 字符串/字符字面量
            if (c == '"') {
                flushWord(word, tokens);
                inString = true;
                continue;
            }
            if (c == '\'') {
                flushWord(word, tokens);
                inChar = true;
                continue;
            }
            // 空白
            if (Character.isWhitespace(c)) {
                flushWord(word, tokens);
                continue;
            }
            // 标识符/数字
            if (Character.isLetterOrDigit(c) || c == '_') {
                word.append(c);
                continue;
            }
            // 运算符/标点：单个 token
            flushWord(word, tokens);
            tokens.add(String.valueOf(c));
        }
        flushWord(word, tokens);
        return inBlockComment;
    }

    private static void flushWord(StringBuilder word, List<String> tokens) {
        if (word.isEmpty()) {
            return;
        }
        String w = word.toString();
        word.setLength(0);
        if (CPP_KEYWORDS.contains(w)) {
            tokens.add(w);
        } else if (w.chars().allMatch(Character::isDigit)) {
            tokens.add(NUMBER_TOKEN);
        } else {
            tokens.add(IDENTIFIER_TOKEN);
        }
    }
}
