package com.musealgo.oj.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 批量报告单条结果。
 */
@Data
@AllArgsConstructor
public class BatchReportItem {

    /** 学生 ID */
    private Long studentId;

    /** 是否生成成功 */
    private boolean success;

    /** 错误信息（成功时为 null） */
    private String error;

    /** 画像快照 ID（成功时有值） */
    private Long snapshotId;

    /** 报告 HTML（始终返回，供前端预览） */
    private String html;

    /** 持久 report_job ID；排队结果也有值。 */
    private Long jobId;

    /** 持久报告状态。 */
    private String status;

    /** 是否已受理；受理不代表报告已生成。 */
    private boolean accepted;

    /** Compatibility constructor retained for existing source callers. */
    public BatchReportItem(Long studentId, boolean success, String error, Long snapshotId, String html) {
        this(studentId, success, error, snapshotId, html, null, null, false);
    }
}
