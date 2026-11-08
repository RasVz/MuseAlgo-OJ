package com.musealgo.oj.analytics.application;

import org.junit.jupiter.api.Test;
import com.musealgo.oj.api.report.ReportActorSnapshot;
import com.musealgo.oj.api.teaching.ClassQuery;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportAccessServiceTest {

    private final ClassQuery classes = mock(ClassQuery.class);
    private final ReportAccessService access = new ReportAccessService(classes);

    @Test
    void explicitWorkerAuthorizationNeedsAConsistentActiveActorAndOwner() {
        when(classes.findById(3L)).thenReturn(Optional.of(new ClassQuery.ClassInfo(3L, "c", 7L)));
        when(classes.findStudentIds(3L)).thenReturn(List.of(11L));
        ReportActorSnapshot teacher = new ReportActorSnapshot(7L, true, false, true);

        assertTrue(access.canActorAccessStudent(7L, teacher, 3L, 11L));
        assertFalse(access.canActorAccessClass(8L, teacher, 3L));
        assertFalse(access.canActorAccessClass(7L, new ReportActorSnapshot(7L, false, true, false), 3L));
        assertFalse(access.canActorAccessStudent(7L, teacher, 3L, 12L));
    }

    @Test
    void explicitAdminStillNeedsExistingClassAndStudentMembership() {
        when(classes.findById(3L)).thenReturn(Optional.of(new ClassQuery.ClassInfo(3L, "c", 8L)));
        when(classes.findStudentIds(3L)).thenReturn(List.of(11L));
        ReportActorSnapshot admin = new ReportActorSnapshot(7L, true, true, false);

        assertTrue(access.canActorAccessStudent(7L, admin, 3L, 11L));
        assertFalse(access.canActorAccessStudent(7L, admin, 3L, 12L));
    }
}
