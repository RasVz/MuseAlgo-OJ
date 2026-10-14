package top.everblue.oj.analytics.report;

/**
 * PDF 渲染失败（Chromium 不可达、超时、返回异常等）。
 * 调用方捕获后应降级为返回 HTML，而不是向用户抛 500。
 */
public class ReportRenderException extends RuntimeException {

    public ReportRenderException(String message) {
        super(message);
    }

    public ReportRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}