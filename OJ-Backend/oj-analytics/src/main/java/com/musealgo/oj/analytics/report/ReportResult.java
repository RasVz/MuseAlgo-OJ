package com.musealgo.oj.analytics.report;

/**
 * 报告生成结果。
 *
 * @param snapshotId    画像快照记录 id
 * @param pdfUrl        兼容旧客户端的字段；始终为 null，禁止公开报告地址
 * @param pdfGenerated  兼容旧客户端的字段；始终为 false
 * @param html          通过已鉴权接口返回的 HTML
 */
public record ReportResult(
        Long snapshotId,
        String pdfUrl,
        boolean pdfGenerated,
        String html,
        Long jobId,
        String status,
        boolean accepted
) {
    /** Compatibility constructor for the internal renderer's existing unit tests. */
    public ReportResult(Long snapshotId, String pdfUrl, boolean pdfGenerated, String html) {
        this(snapshotId, pdfUrl, pdfGenerated, html, null, null, false);
    }
}
