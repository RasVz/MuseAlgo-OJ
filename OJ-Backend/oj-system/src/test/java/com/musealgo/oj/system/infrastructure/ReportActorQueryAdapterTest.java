package com.musealgo.oj.system.infrastructure;

import org.junit.jupiter.api.Test;
import com.musealgo.oj.api.report.ReportActorSnapshot;
import com.musealgo.oj.system.entity.User;
import com.musealgo.oj.system.mapper.UserMapper;
import com.musealgo.oj.system.service.impl.UserDetailsServiceImpl;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportActorQueryAdapterTest {

    private final UserMapper users = mock(UserMapper.class);
    private final UserDetailsServiceImpl details = mock(UserDetailsServiceImpl.class);
    private final ReportActorQueryAdapter adapter = new ReportActorQueryAdapter(users, details);

    @Test
    void returnsEmptyForMissingActorWithoutResolvingRoles() {
        when(users.selectById(8L)).thenReturn(null);

        assertEquals(Optional.empty(), adapter.findCurrentActor(8L));
        verifyNoInteractions(details);
    }

    @Test
    void disabledActorIsReportedInactiveAndDoesNotResolveRoles() {
        User user = new User();
        user.setId(8L);
        user.setIsBanned((byte) 1);
        when(users.selectById(8L)).thenReturn(user);
        when(details.reportActorSnapshot(user)).thenReturn(new ReportActorSnapshot(8L, false, false, false));

        assertEquals(Optional.of(new ReportActorSnapshot(8L, false, false, false)), adapter.findCurrentActor(8L));
        verify(details).reportActorSnapshot(user);
    }

    @Test
    void currentRolesComeFromTheSharedSecurityResolver() {
        User user = new User();
        user.setId(8L);
        when(users.selectById(8L)).thenReturn(user);
        when(details.reportActorSnapshot(user)).thenReturn(new ReportActorSnapshot(8L, true, false, true));

        assertEquals(Optional.of(new ReportActorSnapshot(8L, true, false, true)), adapter.findCurrentActor(8L));
        verify(details).reportActorSnapshot(user);
    }

    @Test
    void resolverFailurePropagatesForFailClosedCallers() {
        User user = new User();
        user.setId(8L);
        when(users.selectById(8L)).thenReturn(user);
        when(details.reportActorSnapshot(user)).thenThrow(new IllegalStateException("database"));

        assertThrows(IllegalStateException.class, () -> adapter.findCurrentActor(8L));
    }
}
