package com.musealgo.oj.analytics.dto;

import com.musealgo.oj.api.report.ReportNotificationIntentStatus;

import java.util.List;

/** Report-owned aggregate: center availability is independent from notice delivery/read facts. */
public record ReportNotificationStatusVO(
        Long jobId,
        boolean centerAvailable,
        Long currentTerminalRevision,
        List<ReportNotificationIntentStatus> intents
) {
    public ReportNotificationStatusVO {
        intents = List.copyOf(intents);
    }
}
