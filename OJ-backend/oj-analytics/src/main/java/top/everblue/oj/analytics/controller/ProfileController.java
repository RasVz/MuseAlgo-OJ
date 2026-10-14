package top.everblue.oj.analytics.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import top.everblue.oj.analytics.application.ProfileApplicationService;
import top.everblue.oj.analytics.application.ReportApplicationService;
import top.everblue.oj.analytics.domain.StudentProfile;
import top.everblue.oj.analytics.dto.BatchReportItem;
import top.everblue.oj.analytics.dto.BatchReportRequest;
import top.everblue.oj.analytics.report.ReportResult;
import top.everblue.oj.common.api.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 学生能力画像与家长报告接口。
 * <p>
 * 当前登录学生查自己:{@code GET /profile/me}
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
    private final ReportApplicationService reportApplicationService;

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
        // 教师权限校验在 application service 内通过 SecurityUtils.requireTeacherOrAdmin 完成
        return Result.success(profileApplicationService.buildStudentProfile(studentId, classId, from, to));
    }

    @Operation(summary = "生成家长周报（教师/管理员）",
            description = "生成指定学生的图文周报 PDF。默认统计上周（周一～周日）；"
                    + "Chromium 不可达时降级返回 HTML（pdfGenerated=false，html 字段有值）")
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
        return Result.success(reportApplicationService.generateWeeklyReport(
                studentId, classId, periodStart, periodEnd));
    }

    @Operation(summary = "批量生成家长周报（教师/管理员）",
            description = "为班级内指定学生批量生成周报 HTML。单学生失败不阻塞他人，"
                    + "返回每学生一条结果（含 HTML，前端可直接预览）。默认为上周（周一～周日）。")
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
        List<BatchReportItem> results = new ArrayList<>();
        for (Long studentId : request.getStudentIds()) {
            try {
                String html = reportApplicationService.generateWeeklyReportHtmlInternal(
                        studentId, request.getClassId(), periodStart, periodEnd);
                results.add(new BatchReportItem(studentId, true, null, null, html));
            } catch (Exception e) {
                log.warn("批量报告生成失败 studentId={}, classId={}: {}",
                        studentId, request.getClassId(), e.getMessage());
                results.add(new BatchReportItem(studentId, false,
                        e.getMessage() != null ? e.getMessage() : "生成失败", null, null));
            }
        }
        return Result.success(results);
    }

    @Operation(summary = "预览周报 HTML（教师/管理员）",
            description = "返回指定学生周报的纯 HTML，供前端 iframe 直接渲染。"
                    + "不走 PDF/COS 流程，始终返回 HTML。默认为上周（周一～周日）。")
    @GetMapping(value = "/report/{studentId}/preview", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    @ResponseBody
    public String previewReportHtml(
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
        return reportApplicationService.generateWeeklyReportHtml(
                studentId, classId, periodStart, periodEnd);
    }
}