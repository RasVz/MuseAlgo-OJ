package com.musealgo.oj.problem.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 题面消毒契约：剥离可执行 HTML，同时不得破坏 Markdown 公式、表格与代码块。
 * 反例按"注入点 → 预期"逐条给出，用于防止后续放宽白名单或误伤正文。
 */
class StatementSanitizerTest {

    @Test
    void removesScriptEventHandlersAndJavascriptUrls() {
        String sanitized = StatementSanitizer.sanitize(
                "<p onclick=\"alert(1)\">safe</p>"
                        + "<script>alert(2)</script>"
                        + "<img src=x onerror=\"alert(3)\">"
                        + "<a href=\"javascript:alert(4)\">link</a>");

        assertThat(sanitized)
                .contains("<p>safe</p>", "<img src=\"x\">", "<a>link</a>")
                .doesNotContainIgnoringCase("script", "onclick", "onerror", "javascript", "alert");
    }

    @Test
    void dropsIframeStyleAndSvgWithTheirContent() {
        String sanitized = StatementSanitizer.sanitize(
                "<iframe src=\"javascript:alert(1)\"></iframe>"
                        + "<style>body{background:url(//evil)}</style>"
                        + "<svg onload=\"alert(2)\"><circle/></svg>正文");

        assertThat(sanitized)
                .isEqualTo("正文");
    }

    @Test
    void keepsFencedCodeVerbatim() {
        String markdown = "```cpp\n#include <iostream>\nstd::vector<int> v;\n```";

        assertThat(StatementSanitizer.sanitize(markdown)).isEqualTo(markdown);
    }

    @Test
    void keepsDangerousTagsInsideFencedCodeVerbatim() {
        String markdown = "```html\n<script>alert(1)</script>\n<img src=x onerror=alert(2)>\n```";

        assertThat(StatementSanitizer.sanitize(markdown)).isEqualTo(markdown);
    }

    @Test
    void keepsInlineCodeVerbatim() {
        String markdown = "行内 `<script>alert(1)</script>` 与 `vector<int>` 保留";

        assertThat(StatementSanitizer.sanitize(markdown)).isEqualTo(markdown);
    }

    @Test
    void dangerousHtmlAfterInlineCodeIsStillRemoved() {
        String sanitized = StatementSanitizer.sanitize("`a` <script>alert(1)</script> `b`");

        assertThat(sanitized)
                .isEqualTo("`a`  `b`")
                .doesNotContainIgnoringCase("script", "alert");
    }

    @Test
    void keepsMarkdownTablesAndLatex() {
        String markdown = "| 列1 | 列2 |\n| --- | --- |\n| 1   | 2   |\n\n给定 $a < b$，复杂度 $O(n\\log n)$。";

        assertThat(StatementSanitizer.sanitize(markdown)).isEqualTo(markdown);
    }

    @Test
    void keepsTableStructureAndSafeAttributes() {
        String sanitized = StatementSanitizer.sanitize(
                "<table><thead><tr><th colspan=\"2\" onclick=\"x\">标题</th></tr></thead>"
                        + "<tbody><tr><td rowspan=\"2\">值</td></tr></tbody></table>");

        assertThat(sanitized)
                .contains("<table>", "<thead>", "<th colspan=\"2\">", "<td rowspan=\"2\">")
                .doesNotContain("onclick");
    }

    @Test
    void keepsRelativeLinksAndImages() {
        String sanitized = StatementSanitizer.sanitize(
                "<a href=\"/problem/1\">站内</a><img src=\"/uploads/a.png\" alt=\"图\">");

        assertThat(sanitized)
                .contains("href=\"/problem/1\"", "src=\"/uploads/a.png\"", "alt=\"图\"");
    }

    @Test
    void keepsUnknownInertTagText() {
        String markdown = "模板开头：#include <iostream> 与 vector<int>";

        assertThat(StatementSanitizer.sanitize(markdown)).isEqualTo(markdown);
    }

    @Test
    void escapesUnparseableTagLikeText() {
        // 不是合法标签（逗号不构成属性），浏览器可能仍按标签解析：转义 '<' 后按文本显示
        assertThat(StatementSanitizer.sanitize("返回 pair<int, int>"))
                .isEqualTo("返回 pair&lt;int, int>");
    }

    @Test
    void escapesMalformedTagsThatBrowsersWouldStillParse() {
        assertThat(StatementSanitizer.sanitize("<img/src=x onerror=alert(1)>"))
                .doesNotContain("<img")
                .contains("&lt;img");
        assertThat(StatementSanitizer.sanitize("<img src=x onerror=alert(1)"))
                .doesNotContain("<img")
                .contains("&lt;img");
        assertThat(StatementSanitizer.sanitize("<script/x>alert(1)</script>"))
                .doesNotContain("<script", "alert(1)</script>");
    }

    @Test
    void dropsMetaAndFormTags() {
        assertThat(StatementSanitizer.sanitize(
                "<meta http-equiv=\"refresh\" content=\"0;url=https://evil.test\">正文<form><input name=x></form>"))
                .isEqualTo("正文");
    }

    @Test
    void dropsUnknownTagCarryingEventHandler() {
        String sanitized = StatementSanitizer.sanitize("<custom-el onclick=\"alert(1)\">内容</custom-el>");

        assertThat(sanitized).isEqualTo("内容");
    }

    @Test
    void removesHtmlComments() {
        assertThat(StatementSanitizer.sanitize("<!-- <script>alert(1)</script> -->ok")).isEqualTo("ok");
    }

    @Test
    void isIdempotent() {
        String source = "`<b>` <p onclick=\"x\">段落</p><script>alert(1)</script><table><tr><td>c</td></tr></table>";

        String once = StatementSanitizer.sanitize(source);

        assertThat(StatementSanitizer.sanitize(once)).isEqualTo(once);
    }

    @Test
    void blankInputBecomesEmpty() {
        assertThat(StatementSanitizer.sanitize(null)).isEmpty();
        assertThat(StatementSanitizer.sanitize("   ")).isEmpty();
    }
}
