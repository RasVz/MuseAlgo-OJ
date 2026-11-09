package com.musealgo.oj.problem.application;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 题面语句（Markdown + 内联 HTML）的统一 HTML 白名单消毒。
 * <p>
 * 为什么不能直接对整段内容调用 {@code Jsoup.clean}：题面在库里是 <b>Markdown</b>（手工编辑）
 * 与 <b>HTML</b>（FPS 导入/格式化落库）混存，前端统一按 Markdown 渲染。若把整段 Markdown
 * 当 HTML 清洗，代码块里的 {@code #include <iostream>}、{@code vector<int>} 会被当成未知标签
 * 删除，模板题面会被改坏。因此这里只处理 <b>标签</b>：
 * </p>
 * <ul>
 *   <li>围栏代码块与行内代码原样保留：Markdown 渲染器会转义其中的内容，不存在执行面；</li>
 *   <li>白名单标签按属性逐个过滤（Jsoup 判定协议与标签归属），保留表格 colspan/rowspan、
 *       图片与链接的安全属性；</li>
 *   <li>危险标签连同内容整体删除（script/style/iframe/svg 等）；</li>
 *   <li>其余未知标签只有在携带事件属性或可执行 URL 时才删除，否则原样保留——
 *       这样普通文本里的 {@code <iostream>}、{@code <T>} 不会被破坏。</li>
 * </ul>
 * <p>
 * 该实现是写入侧的唯一消毒入口（创建/更新、FPS 导入、格式化落库共用），
 * 前端 {@code utils/statement-markup.js} 是同一策略的只读兜底，用于历史数据。
 * </p>
 */
final class StatementSanitizer {

    /** 白名单：Jsoup relaxed + 题面需要的表格结构。 */
    static final Safelist SAFELIST = Safelist.relaxed()
            .addTags("table", "thead", "tbody", "tfoot", "tr", "th", "td")
            .addAttributes("th", "colspan", "rowspan")
            .addAttributes("td", "colspan", "rowspan");

    /** 删除标签及其内容的元素：内容本身可能是脚本、样式或可执行标记。 */
    private static final Set<String> DROP_WITH_CONTENT = Set.of(
            "script", "style", "iframe", "object", "embed", "applet", "frame", "frameset",
            "svg", "math", "noscript", "template", "textarea", "title", "xmp", "plaintext");

    /** 只删除标签、保留正文：这些元素不承载题面内容，留着只会扩大可执行面。 */
    private static final Set<String> DROP_TAG_ONLY = Set.of(
            "meta", "link", "base", "form", "input", "button", "select", "option", "optgroup",
            "video", "audio", "source", "track");

    /** 会触发导航或资源加载的属性：未知标签一旦携带就整体删除。 */
    private static final Set<String> ACTIVE_ATTRIBUTES = Set.of(
            "href", "src", "srcset", "xlink:href", "srcdoc", "formaction", "action",
            "poster", "data", "style", "background", "dynsrc", "lowsrc");

    /** 解析属性的基址：让相对 URL 通过 Jsoup 协议校验，同时保留原值输出。 */
    private static final String ATTRIBUTE_BASE_URI = "https://oj.local/";

    /** 单个标签的最大扫描长度：超过视为正文里的普通小于号，避免吞掉整段内容。 */
    private static final int MAX_TAG_LENGTH = 4096;

    private static final Pattern FENCE_OPEN = Pattern.compile("^ {0,3}(`{3,}|~{3,}).*$");
    private static final Pattern ATTRIBUTE_TEXT = Pattern.compile(
            "^\\s*(?:[A-Za-z_:][-A-Za-z0-9_:.]*(?:\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s\"'=<>`]+))?\\s*)*/?\\s*$");

    private StatementSanitizer() {
    }

    /**
     * 消毒一段题面语句。入参为 Markdown 或 HTML，返回同格式文本；null/空白统一归一为空串。
     */
    static String sanitize(String source) {
        if (!StringUtils.hasText(source)) return "";
        if (source.indexOf('<') < 0) return source;

        List<int[]> protectedRanges = protectedRanges(source);
        StringBuilder out = new StringBuilder(source.length());
        int cursor = 0;
        int rangeIndex = 0;
        while (cursor < source.length()) {
            int lt = source.indexOf('<', cursor);
            if (lt < 0) {
                out.append(source, cursor, source.length());
                break;
            }
            while (rangeIndex < protectedRanges.size() && protectedRanges.get(rangeIndex)[1] <= lt) {
                rangeIndex++;
            }
            if (rangeIndex < protectedRanges.size() && protectedRanges.get(rangeIndex)[0] <= lt
                    && lt < protectedRanges.get(rangeIndex)[1]) {
                int end = Math.min(protectedRanges.get(rangeIndex)[1], source.length());
                out.append(source, cursor, end);
                cursor = end;
                continue;
            }
            out.append(source, cursor, lt);
            Tag tag = parseTag(source, lt);
            if (tag == null) {
                // 疑似标签但语法无法确认：转义 '<'，否则浏览器仍可能按标签解析（如 <img/src=x onerror=1>）
                out.append(looksLikeTagStart(source, lt) ? "&lt;" : "<");
                cursor = lt + 1;
                continue;
            }
            cursor = appendReplacement(out, source, tag);
        }
        return out.toString();
    }

    private static int appendReplacement(StringBuilder out, String source, Tag tag) {
        String name = tag.name();
        if (name.isEmpty()) {
            // 注释/声明/DOCTYPE：不参与渲染，直接删除
            return tag.end();
        }
        if (tag.closing()) {
            if (SAFELIST.isSafeTag(name)) out.append("</").append(name).append('>');
            return tag.end();
        }
        if (DROP_WITH_CONTENT.contains(name)) {
            return skipElement(source, tag.end(), name);
        }
        if (DROP_TAG_ONLY.contains(name)) {
            return tag.end();
        }
        if (SAFELIST.isSafeTag(name)) {
            appendOpenTag(out, tag);
            return tag.end();
        }
        // 未知标签：只有携带可执行属性时才删除；否则原样保留，避免破坏代码片段里的 <xxx>
        if (!hasActiveAttributes(tag.attributes())) {
            out.append(tag.raw());
        }
        return tag.end();
    }

    private static void appendOpenTag(StringBuilder out, Tag tag) {
        out.append('<').append(tag.name());
        for (Attribute attribute : tag.attributes()) {
            if (SAFELIST.isSafeAttribute(tag.name(), tag.attributeHost(), attribute)) {
                out.append(' ').append(attribute.html());
            }
        }
        out.append('>');
    }

    private static boolean hasActiveAttributes(Iterable<Attribute> attributes) {
        for (Attribute attribute : attributes) {
            String key = attribute.getKey().toLowerCase(Locale.ROOT);
            if (key.startsWith("on") || ACTIVE_ATTRIBUTES.contains(key)) return true;
        }
        return false;
    }

    /** 找到同名结束标签，返回其后的位置；找不到时只丢弃起始标签。 */
    private static int skipElement(String source, int from, String name) {
        int index = indexOfIgnoreCase(source, "</" + name, from);
        if (index < 0) return from;
        int close = source.indexOf('>', index);
        return close < 0 ? source.length() : close + 1;
    }

    private static int indexOfIgnoreCase(String source, String needle, int from) {
        int limit = source.length() - needle.length();
        for (int i = Math.max(from, 0); i <= limit; i++) {
            if (source.regionMatches(true, i, needle, 0, needle.length())) return i;
        }
        return -1;
    }

    // ======================== 标签解析 ========================

    private record Tag(String name, boolean closing, String raw, int end,
                       Element attributeHost, Iterable<Attribute> attributes) {
    }

    private static Tag parseTag(String source, int start) {
        int length = source.length();
        if (source.startsWith("<!--", start)) {
            int commentEnd = source.indexOf("-->", start + 4);
            int end = commentEnd < 0 ? length : commentEnd + 3;
            return new Tag("", false, source.substring(start, end), end, null, List.of());
        }
        int index = start + 1;
        if (index >= length) return null;
        char first = source.charAt(index);
        if (first == '!' || first == '?') {
            int end = source.indexOf('>', index);
            if (end < 0 || end - start > MAX_TAG_LENGTH) return null;
            return new Tag("", false, source.substring(start, end + 1), end + 1, null, List.of());
        }
        boolean closing = false;
        if (first == '/') {
            closing = true;
            index++;
        }
        int nameStart = index;
        while (index < length && isNameChar(source.charAt(index), index == nameStart)) index++;
        if (index == nameStart) return null;

        int close = index;
        char quote = 0;
        while (close < length) {
            char current = source.charAt(close);
            if (quote != 0) {
                if (current == quote) quote = 0;
            } else if (current == '"' || current == '\'') {
                quote = current;
            } else if (current == '>') {
                break;
            } else if (close - start > MAX_TAG_LENGTH) {
                return null;
            }
            close++;
        }
        if (close >= length) return null;

        String attributeText = source.substring(index, close);
        if (!ATTRIBUTE_TEXT.matcher(attributeText).matches()) return null;
        if (closing) {
            return attributeText.isBlank()
                    ? new Tag(source.substring(nameStart, index).toLowerCase(Locale.ROOT), true,
                    source.substring(start, close + 1), close + 1, null, List.of())
                    : null;
        }
        String name = source.substring(nameStart, index).toLowerCase(Locale.ROOT);
        Element host = Jsoup.parseBodyFragment("<div" + attributeText + "></div>", ATTRIBUTE_BASE_URI)
                .body().child(0);
        return new Tag(name, false, source.substring(start, close + 1), close + 1, host, host.attributes());
    }

    private static boolean isNameChar(char ch, boolean first) {
        if (ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z') return true;
        if (first) return false;
        return (ch >= '0' && ch <= '9') || ch == '-' || ch == ':';
    }

    /** 判断 '<' 是否可能开启一个标签：是则必须转义，避免浏览器按标签解析。 */
    private static boolean looksLikeTagStart(String source, int lt) {
        int next = lt + 1;
        if (next >= source.length()) return false;
        char ch = source.charAt(next);
        if (ch == '/' || ch == '!' || ch == '?') return true;
        return ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z';
    }

    // ======================== 代码区域识别 ========================

    /** 围栏代码块与行内代码的区间：这些位置由 Markdown 渲染器转义，不参与标签处理。 */
    private static List<int[]> protectedRanges(String source) {
        List<int[]> ranges = new ArrayList<>();
        int pos = 0;
        while (pos <= source.length()) {
            int lineEnd = source.indexOf('\n', pos);
            if (lineEnd < 0) lineEnd = source.length();
            String line = source.substring(pos, lineEnd);
            Matcher fence = FENCE_OPEN.matcher(line);
            if (fence.matches()) {
                String marker = fence.group(1);
                char fenceChar = marker.charAt(0);
                int fenceLength = marker.length();
                int scan = lineEnd + 1;
                int blockEnd = source.length();
                while (scan < source.length()) {
                    int end = source.indexOf('\n', scan);
                    if (end < 0) end = source.length();
                    if (isFenceClose(source.substring(scan, end), fenceChar, fenceLength)) {
                        blockEnd = end;
                        break;
                    }
                    scan = end + 1;
                }
                ranges.add(new int[]{pos, blockEnd});
                pos = blockEnd + 1;
                continue;
            }
            collectInlineCode(line, pos, ranges);
            pos = lineEnd + 1;
        }
        return ranges;
    }

    private static boolean isFenceClose(String line, char fenceChar, int fenceLength) {
        int index = 0;
        while (index < line.length() && index < 3 && line.charAt(index) == ' ') index++;
        int start = index;
        while (index < line.length() && line.charAt(index) == fenceChar) index++;
        if (index - start < fenceLength) return false;
        return line.substring(index).isBlank();
    }

    /** 行内代码：反引号串开启，等长反引号串关闭；未闭合则视为普通文本。 */
    private static void collectInlineCode(String line, int lineOffset, List<int[]> ranges) {
        int index = 0;
        while (index < line.length()) {
            if (line.charAt(index) != '`') {
                index++;
                continue;
            }
            int runStart = index;
            while (index < line.length() && line.charAt(index) == '`') index++;
            int runLength = index - runStart;
            int closeEnd = findClosingRun(line, index, runLength);
            if (closeEnd > 0) {
                ranges.add(new int[]{lineOffset + runStart, lineOffset + closeEnd});
                index = closeEnd;
            }
        }
    }

    private static int findClosingRun(String line, int from, int runLength) {
        int search = from;
        while (search < line.length()) {
            if (line.charAt(search) != '`') {
                search++;
                continue;
            }
            int runStart = search;
            while (search < line.length() && line.charAt(search) == '`') search++;
            if (search - runStart == runLength) return search;
        }
        return -1;
    }
}
