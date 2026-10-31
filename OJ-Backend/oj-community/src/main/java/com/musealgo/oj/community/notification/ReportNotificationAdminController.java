package com.musealgo.oj.community.notification;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.musealgo.oj.common.annotation.AuditLog;
import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.common.utils.SecurityUtils;

import java.util.Map;

/**
 * Admin-only report notification intent queue and one-shot redrive.
 * <p>
 * This endpoint exposes delivery facts only: no report HTML, student names, class/student
 * identifiers, or notification content. The durable outbox columns written by the service are the
 * authoritative audit; {@link AuditLog} is an additional asynchronous record.
 * </p>
 */
@Tag(name = "报告通知队列", description = "管理员查看投递失败提醒并执行唯一一次重投")
@RestController
@RequestMapping("/admin/report-notification-intents")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ReportNotificationAdminController {

    private final ReportNotificationAdminService adminService;

    @Operation(summary = "报告通知意图列表")
    @GetMapping
    public Result<ReportNotificationAdminPage> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String eventKind,
            @RequestParam(required = false) String jobId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long parsedJobId = null;
        if (jobId != null && !jobId.isBlank()) {
            try {
                parsedJobId = Long.valueOf(jobId.trim());
            } catch (NumberFormatException error) {
                return Result.error(400, "任务 ID 必须为数字");
            }
        }
        try {
            return Result.success(adminService.list(status, eventKind, parsedJobId, page, size));
        } catch (IllegalArgumentException error) {
            return Result.error(400, error.getMessage());
        }
    }

    @Operation(summary = "重投一条失败的报告通知")
    @AuditLog(module = "report_notification", action = "redrive")
    @PostMapping("/{intentId}/redrive")
    public Result<?> redrive(
            @PathVariable Long intentId,
            @RequestBody(required = false) RedriveRequest request) {
        ReportNotificationAdminService.RedriveOutcome outcome = adminService.redrive(
                intentId, request == null ? null : request.reason(), SecurityUtils.getUserId());
        if (!outcome.success()) {
            Result<Map<String, String>> failure = Result.error(outcome.resultCode(), outcome.message());
            failure.setData(Map.of("errorCode", outcome.errorCode()));
            return failure;
        }
        return Result.success(outcome.intent());
    }

    /** Redrive request body; the reason is validated to 10-200 code points. */
    public record RedriveRequest(String reason) {
    }
}
