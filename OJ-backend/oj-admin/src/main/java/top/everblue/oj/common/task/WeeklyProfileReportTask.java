package top.everblue.oj.common.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.everblue.oj.analytics.application.ReportApplicationService;
import top.everblue.oj.analytics.report.ReportResult;
import top.everblue.oj.teaching.entity.Class;
import top.everblue.oj.teaching.entity.ClassStudent;
import top.everblue.oj.teaching.service.ClassService;
import top.everblue.oj.teaching.service.ClassStudentService;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 每周一为所有班级学生生成家长周报。
 * <p>
 * 流程：遍历活跃班级 → 班级学生 → 逐个生成（画像 + AI 建议 + PDF + COS + 快照落库）。
 * 单学生失败 catch + log 继续，不阻塞他人；结束后汇总失败列表。
 * <p>
 * 幂等：快照按 (student_id, period_start) upsert，重复执行不会产生重复记录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyProfileReportTask {

    private final ClassService classService;
    private final ClassStudentService classStudentService;
    private final ReportApplicationService reportApplicationService;

    /**
     * 每周一 08:00 执行。
     * 统计周期为上周（上周一～上周日）。
     */
    @Scheduled(cron = "0 0 8 * * MON")
    public void generateWeeklyReports() {
        LocalDate periodStart = LocalDate.now().with(DayOfWeek.MONDAY).minusWeeks(1);
        LocalDate periodEnd = periodStart.plusDays(6);
        log.info("【周报任务】开始生成周报 period={} ~ {}", periodStart, periodEnd);

        List<Class> classes = classService.list();
        if (classes == null || classes.isEmpty()) {
            log.info("【周报任务】无班级，结束");
            return;
        }

        int success = 0;
        int failed = 0;
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
                    ReportResult result = reportApplicationService.generateWeeklyReportInternal(
                            studentId, clazz.getId(), periodStart, periodEnd);
                    success++;
                    log.info("【周报任务】生成成功 studentId={}, pdf={}, snapshotId={}",
                            studentId, result.pdfGenerated(), result.snapshotId());
                } catch (Exception e) {
                    failed++;
                    failures.add("studentId=" + studentId + " classId=" + clazz.getId()
                            + " err=" + e.getMessage());
                    log.error("【周报任务】生成失败 studentId={}, classId={}",
                            studentId, clazz.getId(), e);
                }
            }
        }

        log.info("【周报任务】完成。成功 {} 份，失败 {} 份", success, failed);
        if (!failures.isEmpty()) {
            log.warn("【周报任务】失败明细: {}", failures);
        }
    }
}
