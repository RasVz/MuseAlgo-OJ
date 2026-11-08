package com.musealgo.oj.community.notification;

import java.time.LocalDateTime;

/** Child-process entry point used by {@link ReportNotificationDateTimesZoneCheck}. */
public final class ReportNotificationDateTimesZoneProbe {
    private ReportNotificationDateTimesZoneProbe() { }

    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("Expected one DATETIME wall value");
        System.out.print(ReportNotificationDateTimes.toInstant(LocalDateTime.parse(args[0])));
    }
}
