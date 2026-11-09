package com.musealgo.oj.controller;

import com.musealgo.oj.common.exception.ForbiddenException;
import com.musealgo.oj.system.entity.User;
import com.musealgo.oj.teaching.application.ClassMembershipApplicationService;
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
    private final ClassMembershipApplicationService membership = mock(ClassMembershipApplicationService.class);
    private final ClassController controller = new ClassController(classes, null, null, null, null, membership);

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

    @Test
    void teacherCannotReadAnotherTeachersClassDetail() {
        login(7L, "TEACHER");
        Class target = classOf(3L, 8L, "654321");
        when(classes.getById(3L)).thenReturn(target);
        org.mockito.Mockito.doThrow(new ForbiddenException("无权查看该班级"))
                .when(membership).requireClassReader(3L);

        assertThrows(ForbiddenException.class, () -> controller.getDetail(3L));
    }

    @Test
    void classMemberReadsDetailWithoutInviteCode() {
        login(4L, "STUDENT");
        when(classes.getById(3L)).thenReturn(classOf(3L, 8L, "654321"));
        when(membership.requireClassReader(3L)).thenReturn(false);

        var response = controller.getDetail(3L);

        assertEquals(3L, response.getData().getId());
        assertNull(response.getData().getInviteCode());
    }

    @Test
    void classOwnerReadsDetailWithInviteCode() {
        login(7L, "TEACHER");
        when(classes.getById(3L)).thenReturn(classOf(3L, 7L, "123456"));

        var response = controller.getDetail(3L);

        assertEquals("123456", response.getData().getInviteCode());
    }

    @Test
    void teacherCannotListAnotherTeachersClasses() {
        login(7L, "TEACHER");

        assertThrows(ForbiddenException.class, () -> controller.listCreated(8L));
        verify(classes, never()).list(any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class));
    }

    @Test
    void teacherListsOwnClasses() {
        login(7L, "TEACHER");

        controller.listCreated(7L);

        verify(classes).list(any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class));
    }

    private static Class classOf(Long id, Long teacherId, String inviteCode) {
        Class target = new Class();
        target.setId(id);
        target.setTeacherId(teacherId);
        target.setInviteCode(inviteCode);
        return target;
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
