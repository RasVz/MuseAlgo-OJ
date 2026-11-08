package com.musealgo.oj.controller;

import com.musealgo.oj.common.exception.ForbiddenException;
import com.musealgo.oj.teaching.controller.ClassController;
import com.musealgo.oj.teaching.service.ClassStudentService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ClassHomeworkAccessTest {
    @Test void requesterCannotUseAnotherStudentIdBeforeQueryingMemberships() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        var students = mock(ClassStudentService.class);
        var controller = new ClassController(null, students, null, null, null, null);
        assertThrows(ForbiddenException.class, () -> controller.getMyHomeworks(4L));
        verifyNoInteractions(students);
    }
}
