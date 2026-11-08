package com.musealgo.oj.analytics.application;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.analytics.error.AnalyticsErrorCode;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.musealgo.oj.analytics.dto.ReportJobVO;
import com.musealgo.oj.analytics.dto.ReportNotificationStatusVO;
import com.musealgo.oj.analytics.entity.ReportDelivery;
import com.musealgo.oj.analytics.entity.ReportJob;
import com.musealgo.oj.analytics.mapper.ReportDeliveryMapper;
import com.musealgo.oj.analytics.mapper.ReportJobMapper;
import com.musealgo.oj.api.report.ReportActorQuery;
import com.musealgo.oj.api.report.ReportActorSnapshot;
import com.musealgo.oj.api.report.ReportNotificationStatusQuery;
import com.musealgo.oj.api.report.ReportNotificationStatusSnapshot;
import com.musealgo.oj.api.user.UserQuery;
import com.musealgo.oj.common.exception.ForbiddenException;
import com.musealgo.oj.common.utils.SecurityUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Enqueue-only report API, permission-checked reads, and legacy adapter support. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportJobService {

    private static final Set<String> EXTERNAL_CHANNELS = Set.of("EMAIL", "SMS", "WECHAT");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final ReportJobMapper jobMapper;
    private final ReportDeliveryMapper deliveryMapper;
    private final ReportAccessService accessService;
    private final ReportActorQuery actorQuery;
    private final UserQuery userQuery;
    private final ReportJobLifecycleService lifecycleService;
    private final ReportNotificationStatusQuery notificationStatusQuery;

    @Value("${report.external.dry-run-only:true}")
    private boolean externalDryRunOnly;

    @Transactional(rollbackFor = Exception.class)
    public ReportJobVO requestReport(Long studentId, Long classId,
                                     LocalDate periodStart, LocalDate periodEnd) {
        Long actorId = SecurityUtils.getUserId();
        return enqueue(actorId, studentId, classId, periodStart, periodEnd);
    }

    /** Weekly scheduling uses the teacher selected from the class row, never a request context. */
    @Transactional(rollbackFor = Exception.class)
    public ReportJobVO requestScheduledReport(Long studentId, Long classId, Long teacherId,
                                              LocalDate periodStart, LocalDate periodEnd) {
        return enqueue(teacherId, studentId, classId, periodStart, periodEnd);
    }

    private ReportJobVO enqueue(Long actorId, Long studentId, Long classId,
                                LocalDate periodStart, LocalDate periodEnd) {
        LocalDate[] period = resolvePeriod(periodStart, periodEnd);
        ReportActorSnapshot actor = actorId == null ? null : actorQuery.findCurrentActor(actorId).orElse(null);
        if (!accessService.canActorAccessStudent(actorId, actor, classId, studentId)) {
            throw new ForbiddenException("无权限操作该班级报告");
        }

        ReportJob candidate = new ReportJob();
        candidate.setStudentId(studentId);
        candidate.setStudentName(userQuery.findDisplayInfo(studentId)
                .map(user -> user.nickname() == null || user.nickname().isBlank()
                        ? user.username() : user.nickname())
                .orElse("学生 #" + studentId));
        candidate.setClassId(classId);
        candidate.setRequesterId(actorId);
        candidate.setActiveRequesterId(actorId);
        candidate.setPeriodStart(period[0]);
        candidate.setPeriodEnd(period[1]);
        jobMapper.insertOrLock(candidate);
        ReportJob job = jobMapper.selectForUpdate(candidate.getId());
        if (job == null) throw new IllegalStateException("REPORT_JOB_UNAVAILABLE");
        // POST is an acceptance/status response. HTML is only returned by a generated detail or preview GET.
        return detail(job, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public ReportJobVO retryReport(Long jobId) {
        Long actorId = SecurityUtils.getUserId();
        ReportJob job = lifecycleService.retryFailed(jobId, actorId);
        return detail(job, "GENERATED".equals(job.getStatus()));
    }

    public ReportJobVO getReport(Long jobId) {
        ReportJob job = requireJob(jobId);
        accessService.requireClassTeacher(job.getClassId());
        return detail(job, "GENERATED".equals(job.getStatus()));
    }

    public Page<ReportJobVO> listReports(Long classId, int page, int size) {
        accessService.requireClassTeacher(classId);
        Page<ReportJob> raw = jobMapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size))),
                new LambdaQueryWrapper<ReportJob>()
                        .eq(ReportJob::getClassId, classId)
                        .orderByDesc(ReportJob::getCreateTime));
        Page<ReportJobVO> result = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        result.setRecords(raw.getRecords().stream().map(job -> ReportJobVO.of(job, null, List.of())).toList());
        return result;
    }

    /** Read-only legacy preview: it never creates, retries, or renders a report. */
    public String previewGeneratedReport(Long studentId, Long classId,
                                         LocalDate periodStart, LocalDate periodEnd) {
        accessService.requireClassAndStudent(classId, studentId);
        LocalDate[] period = resolvePeriod(periodStart, periodEnd);
        ReportJob job = jobMapper.findByNaturalKey(studentId, classId, period[0], period[1]);
        if (job == null) throw new ReportPreviewException(404, "REPORT_JOB_NOT_FOUND");
        if ("PENDING".equals(job.getStatus()) || "GENERATING".equals(job.getStatus())) {
            throw new ReportPreviewException(409, "REPORT_NOT_READY");
        }
        if ("FAILED".equals(job.getStatus())) {
            throw new ReportPreviewException(409, "REPORT_GENERATION_FAILED");
        }
        if (!"GENERATED".equals(job.getStatus()) || job.getHtmlContent() == null) {
            throw new ReportPreviewException(409, "REPORT_NOT_READY");
        }
        return job.getHtmlContent();
    }

    public ReportNotificationStatusVO getNotificationStatus(Long jobId) {
        ReportJob job = requireJob(jobId);
        accessService.requireClassTeacher(job.getClassId());
        Long actorId = SecurityUtils.getUserId();
        ReportNotificationStatusSnapshot snapshot = notificationStatusQuery.findForJob(
                jobId, actorId, value(job.getTerminalRevision()));
        return new ReportNotificationStatusVO(jobId, "GENERATED".equals(job.getStatus()),
                value(job.getTerminalRevision()), snapshot.intents());
    }

    @Transactional(rollbackFor = Exception.class)
    public ReportJobVO dryRunExternalDelivery(Long jobId, String channel, Long recipientId) {
        if (!SecurityUtils.isAdmin()) throw new ForbiddenException("仅管理员可操作外部通道");
        String normalized = channel == null ? "" : channel.trim().toUpperCase();
        if (!EXTERNAL_CHANNELS.contains(normalized)) {
            throw new BusinessException(AnalyticsErrorCode.ANALYTICS_INPUT_INVALID, "外部通道仅支持 EMAIL/SMS/WECHAT");
        }
        if (!externalDryRunOnly) throw new BusinessException(AnalyticsErrorCode.REPORT_EXTERNAL_SEND_DISABLED, "禁止关闭外部 dry-run-only");
        if (recipientId == null || recipientId <= 0) throw new BusinessException(AnalyticsErrorCode.ANALYTICS_INPUT_INVALID, "缺少有效接收者");
        ReportJob job = jobMapper.selectForUpdate(jobId);
        if (job == null) throw new BusinessException(AnalyticsErrorCode.REPORT_JOB_NOT_FOUND, "REPORT_JOB_NOT_FOUND");
        if (!"GENERATED".equals(job.getStatus())) throw new BusinessException(AnalyticsErrorCode.REPORT_NOT_READY, "REPORT_NOT_READY");
        ReportDelivery delivery = deliveryMapper.selectOne(new LambdaQueryWrapper<ReportDelivery>()
                .eq(ReportDelivery::getJobId, jobId)
                .eq(ReportDelivery::getChannel, normalized)
                .eq(ReportDelivery::getRecipientId, recipientId));
        LocalDateTime now = jobMapper.currentDatabaseTime();
        if (delivery == null) {
            delivery = new ReportDelivery();
            delivery.setJobId(jobId);
            delivery.setChannel(normalized);
            delivery.setRecipientId(recipientId);
            delivery.setStatus("DRY_RUN");
            delivery.setAttempts(0);
            delivery.setCreateTime(now);
            deliveryMapper.insert(delivery);
        } else {
            delivery.setStatus("DRY_RUN");
            delivery.setUpdateTime(now);
            deliveryMapper.updateById(delivery);
        }
        log.info("Report external delivery dry-run jobId={} channel={}", jobId, normalized);
        return detail(job, true);
    }

    private ReportJobVO detail(ReportJob job, boolean includeHtml) {
        List<ReportJobVO.Delivery> deliveries = deliveryMapper.selectList(
                        new LambdaQueryWrapper<ReportDelivery>().eq(ReportDelivery::getJobId, job.getId())
                                .orderByAsc(ReportDelivery::getId))
                .stream().map(delivery -> new ReportJobVO.Delivery(delivery.getId(), delivery.getChannel(),
                        delivery.getRecipientId(), delivery.getStatus(),
                        delivery.getAttempts() == null ? 0 : delivery.getAttempts(),
                        delivery.getErrorMessage(), delivery.getDeliveredAt())).toList();
        return ReportJobVO.of(job, includeHtml && "GENERATED".equals(job.getStatus())
                ? job.getHtmlContent() : null, deliveries);
    }

    private ReportJob requireJob(Long jobId) {
        ReportJob job = jobMapper.selectById(jobId);
        if (job == null) throw new BusinessException(AnalyticsErrorCode.REPORT_JOB_NOT_FOUND, "REPORT_JOB_NOT_FOUND");
        return job;
    }

    /** Preflight for batch requests so a shared invalid period cannot be accepted per student. */
    public void validatePeriod(LocalDate periodStart, LocalDate periodEnd) {
        resolvePeriod(periodStart, periodEnd);
    }

    private static LocalDate[] resolvePeriod(LocalDate periodStart, LocalDate periodEnd) {
        if (periodStart != null || periodEnd != null) {
            if (periodStart == null || periodEnd == null || periodEnd.isBefore(periodStart)) {
                throw new BusinessException(AnalyticsErrorCode.INVALID_PERIOD, "INVALID_PERIOD");
            }
            return new LocalDate[]{periodStart, periodEnd};
        }
        LocalDate lastMonday = LocalDate.now(BUSINESS_ZONE).with(DayOfWeek.MONDAY).minusWeeks(1);
        return new LocalDate[]{lastMonday, lastMonday.plusDays(6)};
    }

    private static long value(Long value) {
        return value == null ? 0 : value;
    }
}
