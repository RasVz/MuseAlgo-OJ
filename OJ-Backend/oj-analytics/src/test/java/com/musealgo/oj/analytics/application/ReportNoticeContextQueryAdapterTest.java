package com.musealgo.oj.analytics.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.musealgo.oj.analytics.entity.ReportJob;
import com.musealgo.oj.analytics.mapper.ReportJobMapper;
import com.musealgo.oj.api.report.ReportActorQuery;
import com.musealgo.oj.api.report.ReportActorSnapshot;
import com.musealgo.oj.api.report.ReportNoticeContextSnapshot;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReportNoticeContextQueryAdapterTest {

    private final ReportJobMapper jobMapper = mock(ReportJobMapper.class);
    private final ReportActorQuery actorQuery = mock(ReportActorQuery.class);
    private final ReportAccessService accessService = mock(ReportAccessService.class);
    private final ReportNoticeContextQueryAdapter adapter =
            new ReportNoticeContextQueryAdapter(jobMapper, actorQuery, accessService);

    private final ReportJob job = new ReportJob();
    private final ReportActorSnapshot activeTeacher = new ReportActorSnapshot(7L, true, false, true);

    @BeforeEach
    void setUp() {
        job.setId(20L);
        job.setClassId(3L);
        job.setStudentId(11L);
        job.setStatus("GENERATED");
        job.setGenerationNo(4L);
        job.setTerminalRevision(8L);
        job.setActiveRequesterId(7L);

        when(jobMapper.selectForUpdate(20L)).thenReturn(job);
        when(jobMapper.isWithinRetention(eq(20L), anyInt())).thenReturn(true);
        when(actorQuery.findCurrentActor(7L)).thenReturn(Optional.of(activeTeacher));
        when(accessService.canActorAccessStudent(7L, activeTeacher, 3L, 11L)).thenReturn(true);
    }

    @Test
    void wrongRecipientDoesNotMakeMatchingTerminalEventStale() {
        ReportNoticeContextSnapshot snapshot = adapter.lockCurrentContext(
                20L, "REPORT_READY", 4L, 8L, 99L);

        assertAll(
                () -> assertTrue(snapshot.currentTerminal()),
                () -> assertFalse(snapshot.recipientAuthorized()),
                () -> assertTrue(snapshot.withinRetention()),
                () -> assertEquals("AUTH_REVOKED", snapshot.authFailureCode())
        );
        verifyNoInteractions(actorQuery, accessService);
    }

    @Test
    void expiredJobKeepsTerminalAndAuthorizationFactsIndependent() {
        when(jobMapper.isWithinRetention(20L, 1)).thenReturn(false);

        ReportNoticeContextSnapshot snapshot = adapter.lockCurrentContext(
                20L, "REPORT_READY", 4L, 8L, 7L);

        assertAll(
                () -> assertTrue(snapshot.currentTerminal()),
                () -> assertTrue(snapshot.recipientAuthorized()),
                () -> assertFalse(snapshot.withinRetention())
        );
    }

    @Test
    void staleTerminalRevisionDoesNotChangeAuthorizationOrRetentionFacts() {
        ReportNoticeContextSnapshot snapshot = adapter.lockCurrentContext(
                20L, "REPORT_READY", 4L, 9L, 7L);

        assertAll(
                () -> assertFalse(snapshot.currentTerminal()),
                () -> assertTrue(snapshot.recipientAuthorized()),
                () -> assertTrue(snapshot.withinRetention())
        );
    }
}
