package com.musealgo.oj.common.task;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.musealgo.oj.analytics.application.ReportJobService;
import com.musealgo.oj.analytics.dto.ReportJobVO;
import com.musealgo.oj.analytics.entity.ReportJob;
import com.musealgo.oj.teaching.entity.Class;
import com.musealgo.oj.teaching.entity.ClassStudent;
import com.musealgo.oj.teaching.service.ClassService;
import com.musealgo.oj.teaching.service.ClassStudentService;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Automatic weekly pre-generation is opt-in since 2026-10-29. Disabled runs must not touch any
 * class, student or report data, while an explicitly enabled run stays enqueue-only.
 */
class WeeklyProfileReportTaskTest {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final ClassService classService = mock(ClassService.class);
    private final ClassStudentService classStudentService = mock(ClassStudentService.class);
    private final ReportJobService reportJobService = mock(ReportJobService.class);
    private final WeeklyProfileReportTask task =
            new WeeklyProfileReportTask(classService, classStudentService, reportJobService);

    @Test
    void disabledByDefaultDoesNotScheduleAnything() {
        task.generateWeeklyReports();

        verifyNoInteractions(classService, classStudentService, reportJobService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitEnableOnlyEnqueuesAndNeverRenders() {
        ReflectionTestUtils.setField(task, "enabled", true);
        Class clazz = new Class();
        clazz.setId(7L);
        clazz.setName("合成班");
        clazz.setTeacherId(9L);
        when(classService.list()).thenReturn(List.of(clazz));

        LambdaQueryChainWrapper<ClassStudent> query = mock(LambdaQueryChainWrapper.class);
        when(classStudentService.lambdaQuery()).thenReturn(query);
        when(query.eq(any(), any())).thenReturn(query);
        when(query.list()).thenReturn(List.of(member(11L), member(12L)));
        when(reportJobService.requestScheduledReport(any(), any(), any(), any(), any()))
                .thenReturn(pending());

        task.generateWeeklyReports();

        LocalDate start = LocalDate.now(BUSINESS_ZONE).with(DayOfWeek.MONDAY).minusWeeks(1);
        LocalDate end = start.plusDays(6);
        verify(reportJobService).requestScheduledReport(11L, 7L, 9L, start, end);
        verify(reportJobService).requestScheduledReport(12L, 7L, 9L, start, end);
    }

    @Test
    void rejectedItemsAreCountedWithoutAbortingTheRun() {
        ReflectionTestUtils.setField(task, "enabled", true);
        Class clazz = new Class();
        clazz.setId(7L);
        clazz.setName("合成班");
        clazz.setTeacherId(9L);
        when(classService.list()).thenReturn(List.of(clazz));

        @SuppressWarnings("unchecked")
        LambdaQueryChainWrapper<ClassStudent> query = mock(LambdaQueryChainWrapper.class);
        when(classStudentService.lambdaQuery()).thenReturn(query);
        when(query.eq(any(), any())).thenReturn(query);
        when(query.list()).thenReturn(List.of(member(11L)));
        when(reportJobService.requestScheduledReport(eq(11L), eq(7L), eq(9L), any(), any()))
                .thenThrow(new IllegalStateException("synthetic rejection"));

        assertDoesNotThrow(task::generateWeeklyReports);
    }

    private static ClassStudent member(Long studentId) {
        ClassStudent member = new ClassStudent();
        member.setClassId(7L);
        member.setStudentId(studentId);
        return member;
    }

    private static ReportJobVO pending() {
        ReportJob job = new ReportJob();
        job.setId(100L);
        job.setStudentId(11L);
        job.setClassId(7L);
        job.setStatus("PENDING");
        job.setAttempts(0);
        job.setGenerationNo(0L);
        job.setTerminalRevision(0L);
        return ReportJobVO.of(job, null, List.of());
    }
}
