package com.musealgo.oj.analytics.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.musealgo.oj.analytics.application.PracticeAnalyticsService;
import com.musealgo.oj.analytics.application.ProfileApplicationService;
import com.musealgo.oj.analytics.application.ReportAccessService;
import com.musealgo.oj.analytics.application.ReportJobException;
import com.musealgo.oj.analytics.application.ReportJobService;
import com.musealgo.oj.analytics.application.ReportPreviewException;
import com.musealgo.oj.analytics.domain.StudentProfile;
import com.musealgo.oj.analytics.dto.BatchReportItem;
import com.musealgo.oj.analytics.dto.BatchReportRequest;
import com.musealgo.oj.analytics.dto.PracticeAnalyticsVO;
import com.musealgo.oj.analytics.dto.ReportJobVO;
import com.musealgo.oj.analytics.report.ReportResult;
import com.musealgo.oj.common.annotation.AuditLog;
import com.musealgo.oj.common.annotation.RateLimit;
import com.musealgo.oj.common.api.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.LocalDate;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import com.musealgo.oj.common.exception.ForbiddenException;

/**
 * 学生能力画像、练习分析与家长报告接口。
 * <p>
 * 当前登录学生查自己:{@code GET /profile/me}
 * <br>当前登录学生查自己的练习分析:{@code GET /profile/practice/me?range=all|30d}
 * <br>教师查指定学生:{@code GET /profile/student/{studentId}?classId=&from=&to=}
 * <br>教师生成家长周报:{@code POST /profile/report/{studentId}}
 */
@Tag(name = "学生能力画像", description = "基于 tag 与刷题记录的能力画像查询与家长周报")
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/profile")
public class ProfileController {

    private final ProfileApplicationService profileApplicationService;
    private final PracticeAnalyticsService practiceAnalyticsService;
    private final ReportAccessService reportAccessService;
    private final ReportJobService reportJobService;

    @Operation(summary = "我的画像", description = "当前登录学生的能力画像（默认最近 30 天，带 5 分钟缓存）")
    @GetMapping("/me")
    public Result<StudentProfile> myProfile(
            @Parameter(description = "起始日期（含），格式 yyyy-MM-dd，可选")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "截止日期（含），格式 yyyy-MM-dd，可选")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return Result.success(profileApplicationService.buildMyProfile(from, to));
    }

    @Operation(summary = "我的练习分析",
            description = "当前登录学生的练习分析：总览、维度掌握度、标签分布、难度分桶与最近通过/尝试题目。"
                    + "range=all 全部时间，range=30d 最近 30 天（[今天-29 天 00:00, 明天 00:00)）。"
                    + "仅本人，不接收 userId 参数")
    @GetMapping("/practice/me")
    public Result<PracticeAnalyticsVO> myPractice(
            @Parameter(description = "统计范围：all（全部时间）或 30d（最近 30 天），默认 all")
            @RequestParam(required = false, defaultValue = "all") String range
    ) {
        return Result.success(practiceAnalyticsService.analyzeCurrentUser(range));
    }

    @Operation(summary = "学生画像（教师/管理员）", description = "查看指定学生在指定班级下的能力画像")
    @GetMapping("/student/{studentId}")
    public Result<StudentProfile> studentProfile(
            @PathVariable Long studentId,
            @Parameter(description = "班级 ID，用于确定百分位基准与显示名", required = true)
            @RequestParam Long classId,
            @Parameter(description = "起始日期（含），可选")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "截止日期（含），可选")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        // 画像与报告共享班主任归属和学生归属规则。
        return Result.success(profileApplicationService.buildStudentProfile(studentId, classId, from, to));
    }

    @Operation(summary = "生成家长周报（教师/管理员）",
            description = "生成指定学生的图文周报 HTML。默认统计上周（周一～周日）；"
                    + "PDF 由浏览器打印另存，不上传公共存储")
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    @AuditLog(module = "report", action = "generate_weekly_report")
    @PostMapping("/report/{studentId}")
    public Result<ReportResult> generateWeeklyReport(
            @PathVariable Long studentId,
            @Parameter(description = "班级 ID", required = true)
            @RequestParam Long classId,
            @Parameter(description = "周期开始（含），默认上周一")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @Parameter(description = "周期结束（含），默认上周日")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd
    ) {
        ReportJobVO job = reportJobService.requestReport(studentId, classId, periodStart, periodEnd);
        boolean generated = "GENERATED".equals(job.status());
        boolean accepted = !"FAILED".equals(job.status());
        return Result.success(new com.musealgo.oj.analytics.report.ReportResult(
                generated ? job.snapshotId() : null, null, false, null,
                job.id(), job.status(), accepted));
    }

    @Operation(summary = "批量生成家长周报（教师/管理员）",
            description = "为班级内指定学生批量生成周报 HTML。单学生失败不阻塞他人，"
                    + "返回每学生一条结果（含 HTML，前端可直接预览）。默认为上周（周一～周日）。")
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    @AuditLog(module = "report", action = "generate_batch_reports")
    @RateLimit(key = "reportBatch", time = 60, count = 5)
    @PostMapping("/report/batch")
    public Result<List<BatchReportItem>> generateBatchReports(
            @Valid @RequestBody BatchReportRequest request,
            @Parameter(description = "周期开始（含），默认上周一")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @Parameter(description = "周期结束（含），默认上周日")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd
    ) {
        reportAccessService.requireClassTeacher(request.getClassId());
        request.getStudentIds().forEach(id -> reportAccessService.requireStudentInClass(request.getClassId(), id));
        List<BatchReportItem> results = new java.util.ArrayList<>();
        for (Long studentId : request.getStudentIds()) {
            try {
                ReportJobVO job = reportJobService.requestReport(
                        studentId, request.getClassId(), periodStart, periodEnd);
                boolean generated = "GENERATED".equals(job.status());
                boolean accepted = !"FAILED".equals(job.status());
                String error = generated || accepted ? null : job.lastErrorCode();
                results.add(new BatchReportItem(studentId, generated, error,
                        generated ? job.snapshotId() : null, null, job.id(), job.status(), accepted));
            } catch (Exception e) {
                log.warn("Batch report request rejected classId={}", request.getClassId());
                results.add(new BatchReportItem(studentId, false, "REQUEST_REJECTED", null,
                        null, null, null, false));
            }
        }
        return Result.success(results);
    }

    @Operation(summary = "预览周报 HTML（教师/管理员）",
            description = "返回指定学生周报的纯 HTML，供前端 iframe 直接渲染。"
                    + "不走 PDF/COS 流程，始终返回 HTML。默认为上周（周一～周日）。")
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    @AuditLog(module = "report", action = "preview_weekly_report")
    @GetMapping(value = "/report/{studentId}/preview")
    public ResponseEntity<?> previewReportHtml(
            @PathVariable Long studentId,
            @Parameter(description = "班级 ID", required = true)
            @RequestParam Long classId,
            @Parameter(description = "周期开始（含），默认上周一")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @Parameter(description = "周期结束（含），默认上周日")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd
    ) {
        try {
            String html = reportJobService.previewGeneratedReport(studentId, classId, periodStart, periodEnd);
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
        } catch (ReportPreviewException e) {
            return ResponseEntity.status(e.httpStatus())
                    .body(Result.error(e.httpStatus(), e.errorCode()));
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(Result.error(403, "FORBIDDEN"));
        }
    }

    // ---------------- T9-2/T9-3 报告任务 ----------------

    @Operation(summary = "生成/复用学习周报任务（教师/管理员）")
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    @AuditLog(module = "report", action = "request_report_job")
    @PostMapping("/report/jobs")
    public Result<ReportJobVO> requestReportJob(
            @RequestParam Long studentId,
            @RequestParam Long classId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd) {
        return Result.success(reportJobService.requestReport(studentId, classId, periodStart, periodEnd));
    }

    @Operation(summary = "班级报告历史（教师/管理员）")
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    @GetMapping("/report/jobs")
    public Result<Page<ReportJobVO>> listReportJobs(
            @RequestParam Long classId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        return Result.success(reportJobService.listReports(classId,
                page == null ? 1 : page, size == null ? 20 : size));
    }

    @Operation(summary = "报告任务详情（教师/管理员，含 HTML）")
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    // 详情被前端 2 秒轮询；访问控制仍在 service 内校验，审计保留生成/预览/重试等意图性操作。
    @GetMapping("/report/jobs/{jobId}")
    public Result<ReportJobVO> getReportJob(@PathVariable Long jobId) {
        return Result.success(reportJobService.getReport(jobId));
    }

    @Operation(summary = "重试失败报告任务（教师/管理员）")
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    @AuditLog(module = "report", action = "retry_report_job")
    @PostMapping("/report/jobs/{jobId}/retry")
    public Result<ReportJobVO> retryReportJob(@PathVariable Long jobId) {
        try {
            return Result.success(reportJobService.retryReport(jobId));
        } catch (ReportJobException e) {
            return Result.error(409, e.errorCode());
        }
    }

    @Operation(summary = "报告通知状态（仅当前收件人）")
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    @GetMapping("/report/jobs/{jobId}/notification-status")
    public Result<com.musealgo.oj.analytics.dto.ReportNotificationStatusVO> reportNotificationStatus(
            @PathVariable Long jobId) {
        return Result.success(reportJobService.getNotificationStatus(jobId));
    }

    @Operation(summary = "外部通道 dry-run（仅管理员，不实际发送）")
    @PreAuthorize("hasRole('ADMIN')")
    @AuditLog(module = "report", action = "report_external_dry_run")
    @PostMapping("/report/jobs/{jobId}/deliveries/dry-run")
    public Result<ReportJobVO> dryRunExternalDelivery(@PathVariable Long jobId,
                                                      @RequestBody DryRunRequest request) {
        return Result.success(reportJobService.dryRunExternalDelivery(
                jobId, request.channel(), request.recipientId()));
    }

    public record DryRunRequest(String channel, Long recipientId) {
    }
}
