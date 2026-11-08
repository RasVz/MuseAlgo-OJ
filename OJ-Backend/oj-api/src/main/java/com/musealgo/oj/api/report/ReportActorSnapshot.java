package com.musealgo.oj.api.report;

/** Minimal current authorization facts required by the report domain. */
public record ReportActorSnapshot(
        Long userId,
        boolean active,
        boolean admin,
        boolean teacher
) {
}
