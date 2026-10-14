package top.everblue.oj.problem.importer;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 单个 FPS 文件（{@code <fps>} 根节点下的若干 {@code <item>}）的 XML 解析器。
 * <p>
 * 只负责「XML 文本 → 内存中的 {@link FpsProblem}」，不碰数据库、不碰权限、不碰存储。
 * 落库由 {@link FpsImportService} 调用应用层完成。这样拆分是为了让解析逻辑可脱离
 * Spring 上下文单测，且同一份解析结果能同时服务于导入与预览。
 * </p>
 * <p>
 * <b>容错策略分两层，界线很重要：</b>
 * </p>
 * <ol>
 *   <li><b>文件级失败 → 抛异常。</b>XML 本身格式非法、根节点里一个 item 都没有、
 *       超过题目数/解压总量上限——这些错误说明用户传的根本不是可用的题集，
 *       没有「部分成功」可言，直接抛 {@link IllegalArgumentException} 中断整次导入。</li>
 *   <li><b>单题级失败 → 记 warning，不中断。</b>标题缺失、超长截断、测试数据数量不配对、
 *       时间/内存限制单位不认识——都能安全降级，把 {@code <item>} 读完并把问题记进
 *       {@link FpsProblem#warnings()}，交由上层展示给用户。整份题集因为一道题写坏而
 *       全部导入失败，用户体验远差于「导入成功但提示第 3 题有问题」。</li>
 * </ol>
 */
@Component
public class FpsProblemSetParser {

    static final int MAX_PROBLEMS = 5000;
    static final int MAX_CASES_PER_PROBLEM = 1000;
    static final long MAX_TEXT_LENGTH = 128L * 1024L * 1024L;
    private static final int MAX_TITLE_LENGTH = 128;
    private static final int MAX_SOURCE_LENGTH = 200;
    private static final int MAX_TEXT_COLUMN_CODE_POINTS = 16_000;

    /**
     * 解析一份 FPS XML。
     *
     * @param inputStream XML 输入流，由调用方负责关闭；本方法内会读完但不关闭
     * @return 与 {@code <item>} 一一对应的结果列表，成功项带完整题目，失败项带错误原因
     * @throws IllegalArgumentException XML 格式非法、无题目、或超过题目数/文本总量上限
     */
    public List<FpsParsedItem> parse(InputStream inputStream) {
        try {
            var builder = newDocumentBuilderFactory().newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void error(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });
            Document document = builder.parse(inputStream);
            NodeList itemNodes = document.getElementsByTagName("item");
            if (itemNodes.getLength() == 0) {
                throw new IllegalArgumentException("FPS 文件中没有题目");
            }
            if (itemNodes.getLength() > MAX_PROBLEMS) {
                throw new IllegalArgumentException("单次最多导入 " + MAX_PROBLEMS + " 道题目");
            }

            List<FpsParsedItem> items = new ArrayList<>(itemNodes.getLength());
            long totalTextLength = 0;
            for (int index = 0; index < itemNodes.getLength(); index++) {
                Element item = (Element) itemNodes.item(index);
                // 累计文本量用于防「超大 XML」：条目数上限挡不住单个巨型题面。
                totalTextLength += item.getTextContent().length();
                if (totalTextLength > MAX_TEXT_LENGTH) {
                    throw new IllegalArgumentException("FPS 文件解压后内容超过 128 MB 限制");
                }
                try {
                    items.add(FpsParsedItem.success(index + 1, parseItem(item, index + 1)));
                } catch (IllegalArgumentException e) {
                    // 题目级隔离：单题失败降级为一条失败结果，其余题目照常导入。
                    String title = text(item, "title").trim();
                    items.add(FpsParsedItem.failure(index + 1,
                            title.isEmpty() ? "第 " + (index + 1) + " 道题" : title, e.getMessage()));
                }
            }
            return List.copyOf(items);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalArgumentException("FPS XML 格式无效: " + e.getMessage(), e);
        }
    }

    private FpsProblem parseItem(Element item, int position) {
        List<String> warnings = new ArrayList<>();
        String title = text(item, "title").trim();
        if (title.isEmpty()) {
            title = "未命名题目 #" + position;
            warnings.add("原文件缺少标题，已生成占位标题");
        }
        if (title.codePointCount(0, title.length()) > MAX_TITLE_LENGTH) {
            title = truncateCodePoints(title, MAX_TITLE_LENGTH);
            warnings.add("标题超过 128 个字符，已截断");
        }

        String source = text(item, "source").trim();
        if (source.codePointCount(0, source.length()) > MAX_SOURCE_LENGTH) {
            source = truncateCodePoints(source, MAX_SOURCE_LENGTH);
            warnings.add("来源超过 200 个字符，已截断");
        }

        List<String> inputs = texts(item, "test_input");
        List<String> outputs = texts(item, "test_output");
        // 输入/输出按出现顺序两两配对，数量不等时只取能配对的前缀。
        // 不做「智能对齐」：FPS 里两者顺序即语义，错位配对会产出无法察觉的错误测试点。
        if (inputs.size() != outputs.size()) {
            warnings.add("测试输入与输出数量不一致，仅导入前 "
                    + Math.min(inputs.size(), outputs.size()) + " 组可配对数据");
        }
        int testCaseCount = Math.min(inputs.size(), outputs.size());
        if (testCaseCount > MAX_CASES_PER_PROBLEM) {
            warnings.add("测试数据超过 " + MAX_CASES_PER_PROBLEM + " 组，仅导入前 "
                    + MAX_CASES_PER_PROBLEM + " 组");
            testCaseCount = MAX_CASES_PER_PROBLEM;
        }

        List<String> sampleInputs = texts(item, "sample_input");
        List<String> sampleOutputs = texts(item, "sample_output");
        if (sampleInputs.size() != sampleOutputs.size()) {
            warnings.add("样例输入与输出数量不一致，仅保留可配对样例");
        }
        int sampleCount = Math.min(sampleInputs.size(), sampleOutputs.size());
        List<FpsSample> samples = new ArrayList<>(sampleCount);
        for (int index = 0; index < sampleCount; index++) {
            samples.add(new FpsSample(
                    fitTextColumn(sampleInputs.get(index), "样例输入", warnings),
                    fitTextColumn(sampleOutputs.get(index), "样例输出", warnings)));
        }

        int timeLimitMillis = parseTimeLimit(firstElement(item, "time_limit"), warnings);
        int memoryLimitMegabytes = parseMemoryLimit(firstElement(item, "memory_limit"), warnings);

        List<FpsTestCase> testCases = new ArrayList<>(testCaseCount);
        for (int index = 0; index < testCaseCount; index++) {
            testCases.add(new FpsTestCase(inputs.get(index), outputs.get(index)));
        }

        warnings.addAll(importWarnings(item, testCases.isEmpty()));
        return new FpsProblem(
                title,
                text(item, "description"),
                fitTextColumn(text(item, "input"), "输入描述", warnings),
                fitTextColumn(text(item, "output"), "输出描述", warnings),
                List.copyOf(samples),
                fitTextColumn(text(item, "hint"), "提示", warnings),
                source,
                timeLimitMillis,
                memoryLimitMegabytes,
                List.copyOf(testCases),
                List.copyOf(warnings)
        );
    }

    /**
     * XXE 防护：关闭外部实体、外部 DTD 加载与 XInclude。
     * <p>
     * FPS 文件完全来自用户上传，而 DOCTYPE + 外部实体的组合可以让解析器
     * 读取服务器上的任意文件（{@code file:///etc/passwd}）或发起外部请求。
     * 因此这几项<b>不能</b>为了「兼容某些题目里奇怪的 XML」而放开——
     * FPS 题面不需要外部实体，放开等于留一个任意文件读取漏洞。
     * </p>
     */
    private DocumentBuilderFactory newDocumentBuilderFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    /**
     * 时间/内存限制解析失败时降级为默认值，并把失败原因写进 warnings。
     * <p>
     * 限制值不合法不该让整道题导入失败——默认值虽可能与出题方不一致，
     * 但用户可以在题目编辑页手工改；而导入直接失败的话这份题集得重传。
     * </p>
     */
    private int parseTimeLimit(Element element, List<String> warnings) {
        try {
            return parseTimeLimit(element);
        } catch (IllegalArgumentException e) {
            warnings.add(e.getMessage() + "，已使用默认 1000 ms");
            return 1000;
        }
    }

    private int parseTimeLimit(Element element) {
        if (element == null) return 1000;
        BigDecimal value = positiveDecimal(element, "时间限制");
        String unit = unit(element, "s");
        BigDecimal millis = switch (unit) {
            case "s", "sec", "second", "seconds" -> value.multiply(BigDecimal.valueOf(1000L));
            case "ms", "millisecond", "milliseconds" -> value;
            case "us", "microsecond", "microseconds" -> value.divide(BigDecimal.valueOf(1000L), 12, RoundingMode.CEILING);
            default -> throw new IllegalArgumentException("不支持的时间单位: " + unit);
        };
        return safeInt(millis, "时间限制");
    }

    /** 内存限制的降级版，策略同 {@link #parseTimeLimit(Element, List)}，默认 256 MB。 */
    private int parseMemoryLimit(Element element, List<String> warnings) {
        try {
            return parseMemoryLimit(element);
        } catch (IllegalArgumentException e) {
            warnings.add(e.getMessage() + "，已使用默认 256 MB");
            return 256;
        }
    }

    private int parseMemoryLimit(Element element) {
        if (element == null) return 256;
        BigDecimal value = positiveDecimal(element, "内存限制");
        String unit = unit(element, "mb");
        BigDecimal megabytes = switch (unit) {
            case "b", "byte", "bytes" -> value.divide(BigDecimal.valueOf(1024L * 1024L), 12, RoundingMode.CEILING);
            case "kb", "kib" -> value.divide(BigDecimal.valueOf(1024L), 12, RoundingMode.CEILING);
            case "mb", "mib" -> value;
            case "gb", "gib" -> value.multiply(BigDecimal.valueOf(1024L));
            default -> throw new IllegalArgumentException("不支持的内存单位: " + unit);
        };
        return safeInt(megabytes, "内存限制");
    }

    private BigDecimal positiveDecimal(Element element, String fieldName) {
        try {
            BigDecimal value = new BigDecimal(element.getTextContent().trim());
            if (value.signum() <= 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldName + "必须是正数");
        }
    }

    /**
     * 把换算后的限制值收敛为 int，溢出或归零都视为非法。
     * <p>
     * 用 {@link BigDecimal} 而非 double 换算：{@code 0.0000001 s} 这类值
     * 走 double 会有精度误差，往上取整后可能得到 0 ms，
     * 而 0 会被判题侧当作「不限时」或直接导致所有提交超时。
     * </p>
     */
    private int safeInt(BigDecimal value, String fieldName) {
        BigDecimal rounded = value.setScale(0, RoundingMode.CEILING);
        if (rounded.signum() <= 0 || rounded.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(fieldName + "超出系统支持范围");
        }
        try {
            return rounded.intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(fieldName + "超出系统支持范围");
        }
    }

    private String unit(Element element, String defaultUnit) {
        String unit = element.getAttribute("unit");
        return unit == null || unit.isBlank() ? defaultUnit : unit.trim().toLowerCase(Locale.ROOT);
    }

    private Element firstElement(Element parent, String tagName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && tagName.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    private String text(Element parent, String tagName) {
        Element element = firstElement(parent, tagName);
        return element == null ? "" : element.getTextContent();
    }

    private List<String> texts(Element parent, String tagName) {
        List<String> values = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && tagName.equals(element.getTagName())) {
                values.add(element.getTextContent());
            }
        }
        return values;
    }

    private List<String> importWarnings(Element item, boolean withoutTestData) {
        List<String> warnings = new ArrayList<>();
        if (withoutTestData) {
            warnings.add(firstElement(item, "remote_oj") == null
                    ? "文件未包含本地测试数据，仅导入题面"
                    : "远程判题数据不含本地测试点，仅导入题面");
        }
        if (firstElement(item, "spj") != null || firstElement(item, "tpj") != null) {
            warnings.add("特殊裁判程序尚未导入，请发布前手工配置");
        }
        if (firstElement(item, "interactor") != null) {
            warnings.add("交互程序尚未导入，请发布前手工配置");
        }
        if (firstElement(item, "img") != null) {
            warnings.add("内嵌图片资源尚未落盘，请检查题面图片");
        }
        if (firstElement(item, "solution") != null) {
            warnings.add("参考程序未导入");
        }
        return List.copyOf(warnings);
    }

    private String fitTextColumn(String value, String fieldName, List<String> warnings) {
        if (value.codePointCount(0, value.length()) <= MAX_TEXT_COLUMN_CODE_POINTS) return value;
        warnings.add(fieldName + "内容过长，已截断");
        return truncateCodePoints(value, MAX_TEXT_COLUMN_CODE_POINTS);
    }

    /**
     * 按码点而非 char 截断长文本。
     * <p>
     * 直接 {@code substring(0, max)} 会把 UTF-16 代理对切成两半，
     * 产生非法字符，导致后续 HTML 消毒、MySQL utf8mb4 写入或 JSON 序列化报错。
     * 按码点切则能保证结果仍是合法 Unicode。
     * </p>
     */
    private String truncateCodePoints(String value, int maxCodePoints) {
        int end = value.offsetByCodePoints(0, maxCodePoints);
        return value.substring(0, end);
    }

}
