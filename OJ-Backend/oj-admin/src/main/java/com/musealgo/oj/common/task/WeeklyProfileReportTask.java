package com.musealgo.oj.common.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.musealgo.oj.analytics.application.ReportJobService;
import com.musealgo.oj.analytics.dto.ReportJobVO;
import org.springframework.beans.factory.annotation.Value;
import com.musealgo.oj.teaching.entity.Class;
import com.musealgo.oj.teaching.entity.ClassStudent;
import com.musealgo.oj.teaching.service.ClassService;
import com.musealgo.oj.teaching.service.ClassStudentService;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 学习周报预生成任务：显式开启后每周一 03:00（Asia/Shanghai）为所有班级学生受理/复用报告任务。
 * <p>
 * 本任务是 enqueue-only，不等待报告完成：只调用 {@link ReportJobService#requestScheduledReport}
 * 创建或复用持久任务，实际 HTML 由后台 ReportJobWorker 领取并生成。报告中心保留按需受理入口；
 * 两者命中同一 (student_id, class_id, period_start, period_end) 自然键，不会重复创建任务。
 * <p>
 * {@code report.weekly-enabled} 默认关闭（2026-10-29 用户决定），未显式开启时不调度自动周报；
 * 需要时由运维显式设置 {@code OJ_REPORT_WEEKLY_ENABLED=true}。关闭自动调度不影响教师/管理员
 * 按需请求报告。执行时间由 {@code report.weekly-cron} 配置（默认 {@code 0 0 3 * * MON}，
 * 时区固定 Asia/Shanghai）。单学生受理异常只计入 rejected 并记录日志，不阻塞其他学生；
 * 结束后汇总 accepted/rejected 数量，不等待 GENERATED。
 * <p>
 * 幂等：任务按 (student_id, class_id, period_start, period_end) 唯一，重复执行不会产生重复记录，
 * 也不增加 attempts（attempts 只在 worker claim 时增加）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyProfileReportTask {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final ClassService classService;
    private final ClassStudentService classStudentService;
    private final ReportJobService reportJobService;

    @Value("${report.weekly-enabled:false}")
    private boolean enabled;

    /**
     * 按配置的 cron 预生成（默认周一 03:00 Asia/Shanghai）。
     * 统计周期固定为上周（上周一～上周日），与报告中心默认周期一致。
     */
    @Scheduled(cron = "${report.weekly-cron:0 0 3 * * MON}", zone = "Asia/Shanghai")
    public void generateWeeklyReports() {
        if (!enabled) return;
        LocalDate periodStart = LocalDate.now(BUSINESS_ZONE).with(DayOfWeek.MONDAY).minusWeeks(1);
        LocalDate periodEnd = periodStart.plusDays(6);
        log.info("【周报任务】开始受理预生成任务 period={} ~ {}", periodStart, periodEnd);

        List<Class> classes = classService.list();
        if (classes == null || classes.isEmpty()) {
            log.info("【周报任务】无班级，结束");
            return;
        }

        int accepted = 0;
        int rejected = 0;
        List<String> failures = new ArrayList<>();

        for (Class clazz : classes) {
            if (clazz == null || clazz.getId() == null) {
                continue;
            }
            List<Long> studentIds = classStudentService.lambdaQuery()
                    .eq(ClassStudent::getClassId, clazz.getId())
                    .list()
                    .stream()
                    .map(ClassStudent::getStudentId)
                    .distinct()
                    .toList();
            if (studentIds.isEmpty()) {
                continue;
            }

            log.info("【周报任务】班级「{}」共 {} 名学生", clazz.getName(), studentIds.size());
            for (Long studentId : studentIds) {
                try {
                    ReportJobVO result = reportJobService.requestScheduledReport(
                            studentId, clazz.getId(), clazz.getTeacherId(), periodStart, periodEnd);
                    if ("FAILED".equals(result.status())) {
                        rejected++;
                        failures.add("studentId=" + studentId + " classId=" + clazz.getId()
                                + " error=" + result.lastErrorCode());
                        log.warn("【周报任务】受理失败 studentId={}, classId={}, code={}",
                                studentId, clazz.getId(), result.lastErrorCode());
                    } else {
                        accepted++;
                    }
                } catch (Exception e) {
                    rejected++;
                    failures.add("studentId=" + studentId + " classId=" + clazz.getId()
                            + " err=" + e.getMessage());
                    log.error("【周报任务】受理异常 studentId={}, classId={}",
                            studentId, clazz.getId(), e);
                }
            }
        }

        log.info("【周报任务】完成。受理 {} 份，拒绝 {} 份；任务由后台 worker 执行", accepted, rejected);
        if (!failures.isEmpty()) {
            log.warn("【周报任务】失败明细: {}", failures);
        }
    }
}
