package com.musealgo.oj.controller;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.common.exception.ForbiddenException;
import com.musealgo.oj.system.entity.User;
import com.musealgo.oj.teaching.application.ClassMembershipApplicationService;
import com.musealgo.oj.teaching.entity.Class;
import com.musealgo.oj.teaching.error.TeachingErrorCode;
import com.musealgo.oj.teaching.service.ClassService;
import com.musealgo.oj.teaching.service.ClassStudentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ClassMembershipErrorTest {
    private final ClassService classes = mock(ClassService.class);
    private final ClassStudentService members = mock(ClassStudentService.class);
    private final ClassMembershipApplicationService service = new ClassMembershipApplicationService(classes, members);

    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test void removingMissingMemberHasStableNotFoundReason() {
        login(7L, "TEACHER");
        ownedClass(7L);
        when(members.remove(any())).thenReturn(false);
        BusinessException error = assertThrows(BusinessException.class, () -> service.removeMember(3L, 4L));
        assertEquals(TeachingErrorCode.CLASS_MEMBER_NOT_FOUND, error.getErrorCode());
        assertEquals(404, error.getErrorCode().status());
    }

    @Test void removingExistingMemberSucceeds() {
        login(7L, "TEACHER");
        ownedClass(7L);
        when(members.remove(any())).thenReturn(true);
        assertDoesNotThrow(() -> service.removeMember(3L, 4L));
        verify(members).remove(any());
    }

    @Test void databaseFailureIsNotMisreportedAsMissingMember() {
        login(7L, "TEACHER");
        ownedClass(7L);
        var failure = new DataAccessResourceFailureException("synthetic connection loss");
        when(members.remove(any())).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> service.removeMember(3L, 4L)));
    }

    @Test void anotherTeacherCannotProbeOrRemoveMembers() {
        login(8L, "TEACHER");
        ownedClass(7L);
        assertThrows(ForbiddenException.class, () -> service.removeMember(3L, 4L));
        verifyNoInteractions(members);
    }

    @Test void studentCannotProbeOrRemoveMembers() {
        login(4L, "STUDENT");
        assertThrows(ForbiddenException.class, () -> service.removeMember(3L, 4L));
        verifyNoInteractions(classes, members);
    }

    private void ownedClass(long teacher) {
        Class info = new Class(); info.setId(3L); info.setTeacherId(teacher);
        when(classes.getById(3L)).thenReturn(info);
        when(classes.getByIdForUpdate(3L)).thenReturn(info);
    }

    private void login(long id, String role) {
        User user = new User(); user.setId(id);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
