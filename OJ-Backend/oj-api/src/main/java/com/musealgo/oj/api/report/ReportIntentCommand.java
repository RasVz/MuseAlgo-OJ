package com.musealgo.oj.api.report;

/**
 * 报告成功或终态失败时交给通知域持久化的意图上下文。
 * <p>
 * READY 命令的 {@code errorCode} 必须为 null，FAILED 命令须使用安全错误码。
 * {@code recipientId} 仅可在历史报告主体缺失的 FAILED 命令中为 null；该情况
 * 由通知域记录为 UNASSIGNED / SKIPPED_PERMISSION，不会创建虚假收件人或发送通知。
 * </p>
 */
public record ReportIntentCommand(
        Long jobId,
        Long classId,
        Long studentId,
        Long recipientId,
        Long generationNo,
        Long terminalRevision,
        String errorCode
) {
}
