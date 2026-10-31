package com.musealgo.oj.api.report;

import java.util.List;
import java.util.Objects;

/** 当前报告终态修订下、单一收件人的通知事实。 */
public record ReportNotificationStatusSnapshot(
        Long jobId,
        Long currentTerminalRevision,
        List<ReportNotificationIntentStatus> intents
) {
    public ReportNotificationStatusSnapshot {
        Objects.requireNonNull(intents, "intents");
        intents = List.copyOf(intents);
    }
}
