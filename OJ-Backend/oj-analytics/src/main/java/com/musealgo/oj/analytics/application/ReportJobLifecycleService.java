package com.musealgo.oj.analytics.application;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.analytics.error.AnalyticsErrorCode;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.musealgo.oj.analytics.domain.StudentProfile;
import com.musealgo.oj.analytics.entity.ReportDelivery;
import com.musealgo.oj.analytics.entity.ReportJob;
import com.musealgo.oj.analytics.entity.ReportJobAttempt;
import com.musealgo.oj.analytics.mapper.ReportDeliveryMapper;
import com.musealgo.oj.analytics.mapper.ReportJobAttemptMapper;
import com.musealgo.oj.analytics.mapper.ReportJobMapper;
import com.musealgo.oj.api.report.ReportActorQuery;
import com.musealgo.oj.api.report.ReportActorSnapshot;
import com.musealgo.oj.api.report.ReportIntentCommand;
import com.musealgo.oj.api.report.ReportNotificationPort;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/** Short transaction boundaries for claim, fencing, finalization, and retry. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportJobLifecycleService {

    private static final String CHANNEL_IN_APP = "IN_APP";
    private static final String EVENT_READY = "REPORT_READY";
    private static final String EVENT_FAILED = "REPORT_FAILED";
    private static final String PROCESS_WORKER_REF = UUID.randomUUID().toString();

    private final ReportJobMapper jobMapper;
    private final ReportJobAttemptMapper attemptMapper;
    private final ReportDeliveryMapper deliveryMapper;
    private final ReportActorQuery actorQuery;
    private final ReportAccessService accessService;
    private final ReportApplicationService reportApplicationService;
    private final ProfileApplicationService profileApplicationService;
    private final ReportNotificationPort notificationPort;

    @Value("${report.worker.lease-ttl-seconds:60}")
    private int leaseTtlSeconds;

    @Value("${report.worker.max-runtime-seconds:90}")
    private int maxRuntimeSeconds;

    private static final int maxAttempts = ReportFailurePolicy.MAX_ATTEMPTS;

    /** Claims one due row and commits its attempt/lease before returning to the worker. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public ReportJobExecution claimNext() {
        ReportJob job = jobMapper.selectNextDueForUpdate();
        if (job == null) return null;
        normalize(job);

        boolean expiredGeneration = "GENERATING".equals(job.getStatus());
        if (expiredGeneration) {
            recoverExpiredGeneration(job);
            return null;
        }

        if (job.getAttempts() >= maxAttempts) {
            String exhaustedCode = "ATTEMPTS_EXHAUSTED";
            if (jobMapper.failExhaustedPending(job.getId(), maxAttempts, exhaustedCode,
                    ReportFailurePolicy.safeMessage(exhaustedCode)) == 1) {
                stageFailed(job, job.getGenerationNo(), nextRevision(job), exhaustedCode);
            }
            return null;
        }

        String authorizationFailure = authorizationFailure(job.getActiveRequesterId(), job.getClassId(), job.getStudentId());
        if (authorizationFailure != null) {
            int updated = jobMapper.failPendingBeforeClaim(job.getId(), authorizationFailure,
                    ReportFailurePolicy.safeMessage(authorizationFailure));
            if (updated == 1) stageFailed(job, job.getGenerationNo(), nextRevision(job), authorizationFailure);
            return null;
        }

        int attemptNo = job.getAttempts() + 1;
        long generationNo = job.getGenerationNo() + 1;
        String owner = UUID.randomUUID().toString();
        LocalDateTime startedAt = jobMapper.currentDatabaseTime();
        LocalDateTime deadline = startedAt.plusSeconds(Math.max(1, maxRuntimeSeconds));
        int claimed = jobMapper.claimPending(job.getId(), attemptNo, generationNo, owner,
                Math.max(1, leaseTtlSeconds), deadline);
        if (claimed != 1) return null;

        String triggerSource = triggerSource(job);
        attemptMapper.insertClaim(job.getId(), attemptNo, generationNo, triggerSource,
                job.getActiveRequesterId(), PROCESS_WORKER_REF);
        return new ReportJobExecution(job.getId(), job.getStudentId(), job.getStudentName(), job.getClassId(),
                job.getPeriodStart(), job.getPeriodEnd(), job.getActiveRequesterId(), attemptNo, generationNo,
                owner, PROCESS_WORKER_REF, startedAt, deadline, triggerSource);
    }

    private void recoverExpiredGeneration(ReportJob job) {
        String oldLeaseOwner = job.getLeaseOwner();
        ReportJobAttempt priorAttempt = attemptMapper.selectForUpdate(job.getId(), job.getAttempts());
        String expiredCode = priorAttempt == null
                ? "ATTEMPT_AUDIT_UNAVAILABLE"
                : "FINALIZING".equals(priorAttempt.getStage())
                        ? "FINALIZATION_UNAVAILABLE" : "GENERATION_TIMEOUT";
        if (priorAttempt != null) {
            int closed = attemptMapper.finishExpired(job.getId(), job.getAttempts(), job.getGenerationNo(),
                    expiredCode, ReportFailurePolicy.safeMessage(expiredCode));
            if (closed != 1) expiredCode = "ATTEMPT_AUDIT_UNAVAILABLE";
        }

        if (job.getAttempts() >= maxAttempts) {
            String finalCode = "FINALIZATION_UNAVAILABLE".equals(expiredCode)
                    ? expiredCode : "ATTEMPTS_EXHAUSTED";
            if (jobMapper.finishExpiredFailed(job.getId(), job.getGenerationNo(), oldLeaseOwner,
                    maxAttempts, finalCode, ReportFailurePolicy.safeMessage(finalCode)) != 1) return;
            stageFailed(job, job.getGenerationNo(), nextRevision(job), finalCode);
            return;
        }

        String authorizationFailure = authorizationFailure(
                job.getActiveRequesterId(), job.getClassId(), job.getStudentId());
        if (authorizationFailure != null) {
            if (jobMapper.failExpiredBeforeClaim(job.getId(), job.getGenerationNo(), oldLeaseOwner,
                    authorizationFailure, ReportFailurePolicy.safeMessage(authorizationFailure)) == 1) {
                stageFailed(job, job.getGenerationNo(), nextRevision(job), authorizationFailure);
            }
            return;
        }

        int backoff = job.getAttempts() <= 1 ? 5 : 30;
        if (jobMapper.requeueExpired(job.getId(), job.getGenerationNo(), oldLeaseOwner,
                backoff, expiredCode, ReportFailurePolicy.safeMessage(expiredCode)) != 1) {
            throw new IllegalStateException("REPORT_LEASE_RECOVERY_RACE");
        }
    }

    /** Heartbeat CAS; an expired or past-deadline owner cannot renew, even before takeover. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean heartbeat(ReportJobExecution execution) {
        return jobMapper.renewLease(execution.jobId(), execution.generationNo(), execution.leaseOwner(),
                Math.max(1, leaseTtlSeconds), execution.runtimeDeadline()) == 1;
    }

    /** Marks the durable attempt stage before any finalizer transaction begins. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean markFinalizing(ReportJobExecution execution) {
        ReportJob job = jobMapper.selectForUpdate(execution.jobId());
        if (job == null || !jobMapper.hasLiveLease(execution.jobId(), execution.generationNo(),
                execution.leaseOwner(), execution.runtimeDeadline())) return false;
        return attemptMapper.markFinalizing(execution.jobId(), execution.attemptNo(), execution.generationNo()) == 1;
    }

    /**
     * Atomically stores the already-prepared profile, HTML, IN_APP availability, and READY intent.
     * Returns false only when this generation has lost its lease; infrastructure errors propagate.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean finalizeGenerated(ReportJobExecution execution, StudentProfile profile, String html) {
        ReportJob job = jobMapper.selectForUpdate(execution.jobId());
        if (!ownsLiveLease(job, execution)) return false;

        String authorizationFailure = authorizationFailure(job.getActiveRequesterId(), job.getClassId(), job.getStudentId());
        if (authorizationFailure != null) {
            finishAsFailed(job, execution, authorizationFailure);
            return true;
        }
        if (html == null || html.isBlank()) {
            finishAsFailed(job, execution, "EMPTY_RENDER");
            return true;
        }

        var snapshot = profileApplicationService.saveSnapshot(profile);
        if (snapshot == null || snapshot.getId() == null) {
            throw new IllegalStateException("REPORT_SNAPSHOT_UNAVAILABLE");
        }
        if (jobMapper.finishGenerated(job.getId(), execution.generationNo(), execution.leaseOwner(),
                execution.runtimeDeadline(), snapshot.getId(), html) != 1) {
            throw new IllegalStateException("REPORT_FENCE_LOST");
        }
        createInAppDelivery(job, job.getActiveRequesterId());
        if (attemptMapper.finish(job.getId(), execution.attemptNo(), execution.generationNo(),
                "GENERATED", null, null) != 1) {
            throw new IllegalStateException("REPORT_ATTEMPT_AUDIT_UNAVAILABLE");
        }
        notificationPort.stageReadyIntent(new ReportIntentCommand(job.getId(), job.getClassId(),
                job.getStudentId(), job.getActiveRequesterId(), execution.generationNo(),
                nextRevision(job), null));
        return true;
    }

    /** Renderer failure handling is separate from finalization/notice exceptions. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean recordGenerationFailure(ReportJobExecution execution, String errorCode) {
        ReportJob job = jobMapper.selectForUpdate(execution.jobId());
        if (!ownsLiveLease(job, execution)) return false;
        String effectiveCode = authorizationFailure(job.getActiveRequesterId(), job.getClassId(), job.getStudentId());
        if (effectiveCode == null) effectiveCode = errorCode;
        if (ReportFailurePolicy.isAutomaticallyRetryable(effectiveCode)
                && execution.attemptNo() < maxAttempts) {
            int backoff = execution.attemptNo() <= 1 ? 5 : 30;
            if (jobMapper.returnToPending(job.getId(), execution.generationNo(), execution.leaseOwner(),
                    execution.runtimeDeadline(), backoff, effectiveCode,
                    ReportFailurePolicy.safeMessage(effectiveCode)) != 1) return false;
            finishAttempt(job, execution, "RETRY_WAIT", effectiveCode);
            return true;
        }
        finishAsFailed(job, execution, effectiveCode);
        return true;
    }

    /** Explicit retry transition; only it can bind a replacement current requester. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public ReportJob retryFailed(Long jobId, Long actorId) {
        ReportJob job = jobMapper.selectForUpdate(jobId);
        if (job == null) throw new BusinessException(AnalyticsErrorCode.REPORT_JOB_NOT_FOUND, "REPORT_JOB_NOT_FOUND");
        ReportActorSnapshot actor = actorQuery.findCurrentActor(actorId).orElse(null);
        if (!accessService.canActorAccessStudent(actorId, actor, job.getClassId(), job.getStudentId())) {
            throw new com.musealgo.oj.common.exception.ForbiddenException("无权限操作该班级报告");
        }
        if (!"FAILED".equals(job.getStatus())) return job;
        if (!ReportFailurePolicy.canRetry(job)) {
            throw new ReportJobException(job.getAttempts() != null && job.getAttempts() >= maxAttempts
                    ? "ATTEMPTS_EXHAUSTED" : "RETRY_NOT_ALLOWED");
        }
        long revision = value(job.getTerminalRevision());
        notificationPort.supersedePendingFailureIntents(jobId, revision);
        if (jobMapper.retryFailed(jobId, actorId, revision, maxAttempts) != 1) {
            throw new IllegalStateException("REPORT_RETRY_RACE");
        }
        return jobMapper.selectForUpdate(jobId);
    }

    /** Read-only count used by the worker's release gate; never mutates a row. */
    public int countLegacyGenerating() {
        return jobMapper.countLegacyGenerating();
    }

    /** Opt-in upgrade recovery for legacy rows whose pre-lease workers were externally stopped. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int recoverLegacyGenerating() {
        int recovered = 0;
        for (ReportJob job : jobMapper.selectLegacyGeneratingForUpdate(500)) {
            normalize(job);
            if (job.getAttempts() < maxAttempts) {
                if (hasLedgerAttempt(job.getAttempts())) {
                    attemptMapper.insertClaim(job.getId(), job.getAttempts(), job.getGenerationNo(),
                            "LEGACY_RECOVERY", job.getActiveRequesterId(), PROCESS_WORKER_REF);
                    attemptMapper.finish(job.getId(), job.getAttempts(), job.getGenerationNo(),
                            "LEGACY_GENERATING_RECOVERED", "LEGACY_GENERATING_RECOVERED",
                            ReportFailurePolicy.safeMessage("LEGACY_GENERATING_RECOVERED"));
                }
                recovered += jobMapper.recoverLegacyPending(job.getId(), maxAttempts);
            } else {
                String code = "ATTEMPTS_EXHAUSTED";
                if (hasLedgerAttempt(job.getAttempts())) {
                    attemptMapper.insertClaim(job.getId(), job.getAttempts(), job.getGenerationNo(),
                            "LEGACY_RECOVERY", job.getActiveRequesterId(), PROCESS_WORKER_REF);
                    attemptMapper.finish(job.getId(), job.getAttempts(), job.getGenerationNo(),
                            "ATTEMPTS_EXHAUSTED", code, ReportFailurePolicy.safeMessage(code));
                }
                int failed = jobMapper.recoverLegacyFailed(job.getId(), maxAttempts);
                if (failed == 1) {
                    stageFailed(job, job.getGenerationNo(), nextRevision(job), code);
                    recovered++;
                }
            }
        }
        return recovered;
    }

    private boolean ownsLiveLease(ReportJob job, ReportJobExecution execution) {
        return job != null && "GENERATING".equals(job.getStatus())
                && value(job.getGenerationNo()) == execution.generationNo()
                && execution.leaseOwner().equals(job.getLeaseOwner())
                && jobMapper.hasLiveLease(execution.jobId(), execution.generationNo(),
                        execution.leaseOwner(), execution.runtimeDeadline());
    }

    private void finishAsFailed(ReportJob job, ReportJobExecution execution, String code) {
        if (jobMapper.finishFailed(job.getId(), execution.generationNo(), execution.leaseOwner(),
                execution.runtimeDeadline(), code, ReportFailurePolicy.safeMessage(code)) != 1) {
            throw new IllegalStateException("REPORT_FENCE_LOST");
        }
        finishAttempt(job, execution, code, code);
        stageFailed(job, execution.generationNo(), nextRevision(job), code);
    }

    private void finishAttempt(ReportJob job, ReportJobExecution execution, String outcome, String code) {
        if (attemptMapper.finish(job.getId(), execution.attemptNo(), execution.generationNo(),
                outcome, code, code == null ? null : ReportFailurePolicy.safeMessage(code)) != 1) {
            throw new IllegalStateException("REPORT_ATTEMPT_AUDIT_UNAVAILABLE");
        }
    }

    private void createInAppDelivery(ReportJob job, Long recipientId) {
        ReportDelivery delivery = deliveryMapper.selectOne(new LambdaQueryWrapper<ReportDelivery>()
                .eq(ReportDelivery::getJobId, job.getId())
                .eq(ReportDelivery::getChannel, CHANNEL_IN_APP)
                .eq(ReportDelivery::getRecipientId, recipientId));
        LocalDateTime now = jobMapper.currentDatabaseTime();
        if (delivery == null) {
            delivery = new ReportDelivery();
            delivery.setJobId(job.getId());
            delivery.setChannel(CHANNEL_IN_APP);
            delivery.setRecipientId(recipientId);
            delivery.setStatus("DELIVERED");
            delivery.setAttempts(1);
            delivery.setDeliveredAt(now);
            delivery.setCreateTime(now);
            deliveryMapper.insert(delivery);
        } else {
            delivery.setStatus("DELIVERED");
            delivery.setDeliveredAt(now);
            delivery.setUpdateTime(now);
            deliveryMapper.updateById(delivery);
        }
    }

    private String authorizationFailure(Long actorId, Long classId, Long studentId) {
        if (actorId == null) return "LEGACY_ACTOR_MISSING";
        Optional<ReportActorSnapshot> actor = actorQuery.findCurrentActor(actorId);
        if (actor.isEmpty()) return "LEGACY_ACTOR_MISSING";
        if (!actor.get().active() || !(actor.get().admin() || actor.get().teacher())) return "AUTH_REVOKED";
        return accessService.canActorAccessStudent(actorId, actor.get(), classId, studentId)
                ? null : "AUTH_REVOKED";
    }

    private void stageFailed(ReportJob job, Long generationNo, Long terminalRevision, String code) {
        notificationPort.stageFailedIntent(new ReportIntentCommand(job.getId(), job.getClassId(),
                job.getStudentId(), job.getActiveRequesterId(), generationNo, terminalRevision, code));
    }

    private static Long nextRevision(ReportJob job) {
        return value(job.getTerminalRevision()) + 1;
    }

    private static long value(Long value) {
        return value == null ? 0 : value;
    }

    private static boolean hasLedgerAttempt(Integer attemptNo) {
        return attemptNo != null && attemptNo >= 1 && attemptNo <= ReportFailurePolicy.MAX_ATTEMPTS;
    }

    private static void normalize(ReportJob job) {
        if (job.getAttempts() == null) job.setAttempts(0);
        if (job.getGenerationNo() == null) job.setGenerationNo(0L);
        if (job.getTerminalRevision() == null) job.setTerminalRevision(0L);
    }

    private static String triggerSource(ReportJob job) {
        if (job.getLastErrorCode() != null && job.getNextAttemptAt() != null) return "AUTO_RETRY";
        if (job.getAttempts() > 0 || job.getTerminalRevision() > 0) return "USER_RETRY";
        return "INITIAL_ENQUEUE";
    }
}
