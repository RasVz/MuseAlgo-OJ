package com.musealgo.oj.community.notification;

import java.util.List;

/** Server-paged administrator list of report notification intents. */
public record ReportNotificationAdminPage(
        List<ReportNotificationAdminIntent> items,
        long total,
        int page,
        int size
) {
    public ReportNotificationAdminPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
