package top.everblue.oj.analytics.report;

/**
 * 报告生成结果。
 *
 * @param snapshotId    画像快照记录 id
 * @param pdfUrl        PDF 的 COS 地址；PDF 未生成或上传失败时为 null
 * @param pdfGenerated  是否成功产出 PDF
 * @param html          PDF 未生成时的降级 HTML（前端可转链预览）；成功时为 null
 */
public record ReportResult(
        Long snapshotId,
        String pdfUrl,
        boolean pdfGenerated,
        String html
) {}