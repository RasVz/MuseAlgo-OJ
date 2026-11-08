package com.musealgo.oj.analytics.application;

import org.junit.jupiter.api.Test;
import com.musealgo.oj.analytics.dto.ReportJobVO;
import com.musealgo.oj.analytics.entity.ReportJob;
import com.musealgo.oj.analytics.mapper.ReportDeliveryMapper;
import com.musealgo.oj.analytics.mapper.ReportJobMapper;
import com.musealgo.oj.api.report.ReportActorQuery;
import com.musealgo.oj.api.report.ReportActorSnapshot;
import com.musealgo.oj.api.report.ReportNotificationStatusQuery;
import com.musealgo.oj.api.user.UserDisplayInfo;
import com.musealgo.oj.api.user.UserQuery;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * On-demand acceptance is a separate capability from the automatic weekly scheduler. This pins the
 * service seam used by both: it only inserts/reads the natural-key job and returns its status.
 */
class ReportJobServiceTest {

    private final ReportJobMapper jobMapper = mock(ReportJobMapper.class);
    private final ReportDeliveryMapper deliveryMapper = mock(ReportDeliveryMapper.class);
    private final ReportAccessService accessService = mock(ReportAccessService.class);
    private final ReportActorQuery actorQuery = mock(ReportActorQuery.class);
    private final UserQuery userQuery = mock(UserQuery.class);
    private final ReportJobLifecycleService lifecycleService = mock(ReportJobLifecycleService.class);
    private final ReportNotificationStatusQuery notificationStatusQuery = mock(ReportNotificationStatusQuery.class);
    private final ReportJobService service = new ReportJobService(jobMapper, deliveryMapper, accessService,
            actorQuery, userQuery, lifecycleService, notificationStatusQuery);

    @Test
    void scheduledAcceptanceEnqueuesWithoutDependingOnAutomaticScheduling() {
        LocalDate start = LocalDate.of(2026, 10, 19);
        LocalDate end = start.plusDays(6);
        ReportActorSnapshot teacher = new ReportActorSnapshot(9L, true, false, true);
        when(actorQuery.findCurrentActor(9L)).thenReturn(Optional.of(teacher));
        when(accessService.canActorAccessStudent(9L, teacher, 7L, 11L)).thenReturn(true);
        when(userQuery.findDisplayInfo(11L)).thenReturn(Optional.of(
                new UserDisplayInfo(11L, "student", "学生", null, null, null, 0)));
        when(jobMapper.insertOrLock(any(ReportJob.class))).thenAnswer(invocation -> {
            ReportJob candidate = invocation.getArgument(0);
            candidate.setId(100L);
            return 1;
        });
        ReportJob stored = new ReportJob();
        stored.setId(100L);
        stored.setStudentId(11L);
        stored.setClassId(7L);
        stored.setPeriodStart(start);
        stored.setPeriodEnd(end);
        stored.setStatus("PENDING");
        stored.setAttempts(0);
        stored.setGenerationNo(0L);
        stored.setTerminalRevision(0L);
        when(jobMapper.selectForUpdate(100L)).thenReturn(stored);
        when(deliveryMapper.selectList(any())).thenReturn(List.of());

        ReportJobVO accepted = service.requestScheduledReport(11L, 7L, 9L, start, end);

        assertEquals(100L, accepted.id());
        assertEquals("PENDING", accepted.status());
        assertEquals(0, accepted.attempts());
        assertNull(accepted.html());
        verify(jobMapper).insertOrLock(any(ReportJob.class));
        verify(lifecycleService, org.mockito.Mockito.never()).claimNext();
        verify(lifecycleService, org.mockito.Mockito.never())
                .finalizeGenerated(any(), any(), any());
    }
}
