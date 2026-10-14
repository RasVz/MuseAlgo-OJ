package top.everblue.oj.analytics.report;

/**
 * 报告渲染器：HTML → PDF 字节流。
 * <p>
 * 实现方对接 headless Chromium（browserless/chrome）。
 * 渲染失败抛 {@link ReportRenderException}，由调用方降级。
 */
public interface ReportRenderer {

    /**
     * 把完整 HTML（含内嵌图表脚本）渲染成 A4 PDF。
     *
     * @param html 自包含 HTML（外部依赖仅限公网 CDN）
     * @return PDF 字节流
     * @throws ReportRenderException 渲染失败（不可达/超时/异常响应）
     */
    byte[] renderPdf(String html);
}