package com.musealgo.oj.analytics.application;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.musealgo.oj.analytics.entity.ReportJob;
import com.musealgo.oj.analytics.mapper.ReportJobMapper;
import com.musealgo.oj.api.report.ReportActorQuery;
import com.musealgo.oj.api.report.ReportActorSnapshot;
import com.musealgo.oj.api.report.ReportNoticeContextQuery;
import com.musealgo.oj.api.report.ReportNoticeContextSnapshot;

/** Report-owned transactional authorization seam consumed by the notification domain. */
@Service
@RequiredArgsConstructor
public class ReportNoticeContextQueryAdapter implements ReportNoticeContextQuery {

    private final ReportJobMapper jobMapper;
    private final ReportActorQuery actorQuery;
    private final ReportAccessService accessService;

    @Value("${report.retention-months:12}")
    private int retentionMonths;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ReportNoticeContextSnapshot lockCurrentContext(Long jobId, String eventKind,
                                                           Long generationNo, Long terminalRevision,
                                                           Long recipientId) {
        ReportJob job = jobMapper.selectForUpdate(jobId);
        if (job == null) {
            return new ReportNoticeContextSnapshot(jobId, null, null, "NOT_FOUND", null, null,
                    null, recipientId, false, false, false, "REPORT_JOB_NOT_FOUND");
        }

        String requiredStatus = switch (eventKind == null ? "" : eventKind) {
            case "REPORT_READY" -> "GENERATED";
            case "REPORT_FAILED" -> "FAILED";
            default -> "";
        };
        // Lifecycle currency is only status/generation/revision; authorization and retention
        // are independent facts returned to the caller.
        boolean currentTerminal = !requiredStatus.isEmpty()
                && requiredStatus.equals(job.getStatus())
                && equal(generationNo, job.getGenerationNo())
                && equal(terminalRevision, job.getTerminalRevision());
        boolean withinRetention = jobMapper.isWithinRetention(jobId, Math.max(1, retentionMonths));

        String authFailureCode = null;
        boolean authorized = false;
        if (recipientId == null || job.getActiveRequesterId() == null) {
            authFailureCode = "LEGACY_ACTOR_MISSING";
        } else if (!recipientId.equals(job.getActiveRequesterId())) {
            authFailureCode = "AUTH_REVOKED";
        } else {
            ReportActorSnapshot actor = actorQuery.findCurrentActor(recipientId).orElse(null);
            if (actor == null) {
                authFailureCode = "LEGACY_ACTOR_MISSING";
            } else if (!actor.active() || !(actor.admin() || actor.teacher())) {
                authFailureCode = "AUTH_REVOKED";
            } else {
                authorized = accessService.canActorAccessStudent(recipientId, actor,
                        job.getClassId(), job.getStudentId());
                if (!authorized) authFailureCode = "AUTH_REVOKED";
            }
        }

        return new ReportNoticeContextSnapshot(job.getId(), job.getClassId(), job.getStudentId(),
                job.getStatus(), job.getGenerationNo(), job.getTerminalRevision(),
                job.getActiveRequesterId(), recipientId, currentTerminal, authorized,
                withinRetention, authFailureCode);
    }

    private static boolean equal(Long left, Long right) {
        return left != null && left.equals(right);
    }
}
