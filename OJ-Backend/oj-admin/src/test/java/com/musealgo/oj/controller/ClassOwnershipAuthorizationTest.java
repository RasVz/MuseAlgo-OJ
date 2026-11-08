package com.musealgo.oj.controller;

import com.musealgo.oj.common.exception.ForbiddenException;
import com.musealgo.oj.system.entity.User;
import com.musealgo.oj.teaching.controller.ClassController;
import com.musealgo.oj.teaching.entity.Class;
import com.musealgo.oj.teaching.service.ClassService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClassOwnershipAuthorizationTest {
    private final ClassService classes = mock(ClassService.class);
    private final ClassController controller = new ClassController(classes, null, null, null, null, null);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void studentCannotCreateClass() {
        login(4L, "STUDENT");

        assertThrows(ForbiddenException.class, () -> controller.create(classRequest(3L)));
        verifyNoInteractions(classes);
    }

    @Test
    void teacherCannotCreateClassForAnotherTeacher() {
        login(7L, "TEACHER");

        assertThrows(ForbiddenException.class, () -> controller.create(classRequest(3L)));
        verifyNoInteractions(classes);
    }

    @Test
    void teacherCreatesOwnClassWithoutRequestControlledGeneratedFields() {
        login(7L, "TEACHER");
        when(classes.createClass(any(Class.class))).thenReturn(true);
        Class request = classRequest(7L);
        request.setId(900L);
        request.setStudentCount(99);
        request.setInviteCode("forged-code");

        controller.create(request);

        var captor = org.mockito.ArgumentCaptor.forClass(Class.class);
        verify(classes).createClass(captor.capture());
        Class persisted = captor.getValue();
        assertEquals(7L, persisted.getTeacherId());
        assertEquals("Synthetic class", persisted.getName());
        assertEquals("Synthetic description", persisted.getDescription());
        assertEquals("Synthetic school", persisted.getSchool());
        assertNull(persisted.getId());
        assertNull(persisted.getStudentCount());
        assertNull(persisted.getInviteCode());
    }

    @Test
    void adminCanCreateClassForSelectedTeacher() {
        login(99L, "ADMIN");
        when(classes.createClass(any(Class.class))).thenReturn(true);

        controller.create(classRequest(3L));

        var captor = org.mockito.ArgumentCaptor.forClass(Class.class);
        verify(classes).createClass(captor.capture());
        assertEquals(3L, captor.getValue().getTeacherId());
    }

    @Test
    void studentCannotMintInviteCode() {
        login(4L, "STUDENT");

        assertThrows(ForbiddenException.class, () -> controller.getInviteCode(3L));
        verifyNoInteractions(classes);
    }

    @Test
    void teacherCanMintInviteOnlyForOwnedClass() {
        login(7L, "TEACHER");
        Class target = new Class();
        target.setId(3L);
        target.setTeacherId(8L);
        when(classes.getById(3L)).thenReturn(target);

        assertThrows(ForbiddenException.class, () -> controller.getInviteCode(3L));
        verify(classes, never()).createInviteCode(3L);

        target.setTeacherId(7L);
        when(classes.createInviteCode(3L)).thenReturn("123456");
        controller.getInviteCode(3L);
        verify(classes).createInviteCode(3L);
    }

    @Test
    void missingClassCannotReceiveInviteCode() {
        login(99L, "ADMIN");
        when(classes.getById(3L)).thenReturn(null);

        controller.getInviteCode(3L);

        verify(classes, never()).createInviteCode(3L);
    }

    private static Class classRequest(Long teacherId) {
        Class request = new Class();
        request.setName("Synthetic class");
        request.setDescription("Synthetic description");
        request.setSchool("Synthetic school");
        request.setTeacherId(teacherId);
        return request;
    }

    private static void login(long id, String role) {
        User user = new User();
        user.setId(id);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
