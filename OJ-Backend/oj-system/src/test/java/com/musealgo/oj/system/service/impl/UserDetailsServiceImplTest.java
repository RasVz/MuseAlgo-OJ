package com.musealgo.oj.system.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.musealgo.oj.system.entity.User;
import com.musealgo.oj.system.mapper.UserAuthorityMapper;
import com.musealgo.oj.system.mapper.UserMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserDetailsServiceImplTest {

    private final UserMapper users = mock(UserMapper.class);
    private final UserAuthorityMapper grants = mock(UserAuthorityMapper.class);

    @Test
    void reportActorSnapshotUsesLegacyRoleMappingWhenNormalizedRbacIsOff() {
        UserDetailsServiceImpl service = new UserDetailsServiceImpl(users, grants);
        User user = user((byte) 2, (byte) 0);

        var snapshot = service.reportActorSnapshot(user);

        assertTrue(snapshot.active());
        assertFalse(snapshot.admin());
        assertTrue(snapshot.teacher());
        verifyNoInteractions(grants);
    }

    @Test
    void reportActorSnapshotUsesOnlyNormalizedAuthoritiesWhenEnabled() {
        UserDetailsServiceImpl service = new UserDetailsServiceImpl(users, grants);
        ReflectionTestUtils.setField(service, "normalizedRbacEnabled", true);
        User user = user((byte) 3, (byte) 0); // Legacy admin role must not be a fallback.
        when(grants.selectAuthorityCodes(7L)).thenReturn(List.of("ROLE_TEACHER"));

        var snapshot = service.reportActorSnapshot(user);

        assertTrue(snapshot.active());
        assertFalse(snapshot.admin());
        assertTrue(snapshot.teacher());
        verify(grants).selectAuthorityCodes(7L);
    }

    @Test
    void disabledOrUnmappedActorsFailClosed() {
        UserDetailsServiceImpl service = new UserDetailsServiceImpl(users, grants);
        ReflectionTestUtils.setField(service, "normalizedRbacEnabled", true);
        User disabled = user((byte) 3, (byte) 1);
        var disabledSnapshot = service.reportActorSnapshot(disabled);
        assertFalse(disabledSnapshot.active());
        assertFalse(disabledSnapshot.admin());
        verifyNoInteractions(grants);

        User unmapped = user((byte) 3, (byte) 0);
        when(grants.selectAuthorityCodes(7L)).thenReturn(List.of());
        var unmappedSnapshot = service.reportActorSnapshot(unmapped);
        assertTrue(unmappedSnapshot.active());
        assertFalse(unmappedSnapshot.admin());
        assertFalse(unmappedSnapshot.teacher());
    }

    private User user(Byte role, Byte banned) {
        User user = new User();
        user.setId(7L);
        user.setRole(role);
        user.setIsBanned(banned);
        return user;
    }
}
