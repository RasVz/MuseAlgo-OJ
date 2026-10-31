package com.musealgo.oj.analytics.application;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Immutable lease token handed from the claim transaction to one worker slot. */
public record ReportJobExecution(
        Long jobId,
        Long studentId,
        String studentName,
        Long classId,
        LocalDate periodStart,
        LocalDate periodEnd,
        Long actorId,
        int attemptNo,
        long generationNo,
        String leaseOwner,
        String workerRef,
        LocalDateTime startedAt,
        LocalDateTime runtimeDeadline,
        String triggerSource
) {
}
