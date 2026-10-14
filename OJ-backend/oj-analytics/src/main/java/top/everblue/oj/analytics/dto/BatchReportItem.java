package top.everblue.oj.analytics.dto;

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
}
