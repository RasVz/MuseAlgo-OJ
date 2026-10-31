package com.musealgo.oj.community.notification;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Converts MySQL DATETIME wall-clock values using the repository's explicit DB zone. */
final class ReportNotificationDateTimes {
    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Shanghai");

    private ReportNotificationDateTimes() { }

    static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(DATABASE_ZONE).toInstant();
    }
}
