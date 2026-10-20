package top.everblue.oj.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import top.everblue.oj.common.exception.ForbiddenException;
import top.everblue.oj.system.entity.User;
import top.everblue.oj.teaching.controller.ClassCoursewareController;
import top.everblue.oj.teaching.service.ClassCoursewareService;
import top.everblue.oj.teaching.service.CoursewareService;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeachingAuthorizationTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    void login(long id, String role) {
        User user = new User(); user.setId(id);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role))));
    }
    @Test void studentCannotMutateClassCourseware() {
        login(10, "STUDENT");
        var controller = new ClassCoursewareController(mock(ClassCoursewareService.class), mock(CoursewareService.class), mock(top.everblue.oj.teaching.application.ClassMembershipApplicationService.class));
        assertThrows(ForbiddenException.class, () -> controller.updateClassCourseware(Map.of("id", 1, "sort", 2)));
    }
    @Test void teacherCanOnlyModifyOwnClassAndStudentCannotRequestHiddenItems() {
        var classes = mock(top.everblue.oj.teaching.service.ClassService.class);
        var students = mock(top.everblue.oj.teaching.service.ClassStudentService.class);
        var membership = new top.everblue.oj.teaching.application.ClassMembershipApplicationService(classes, students);
        var cls = new top.everblue.oj.teaching.entity.Class(); cls.setId(3L); cls.setTeacherId(7L);
        when(classes.getById(3L)).thenReturn(cls);
        var relations = mock(ClassCoursewareService.class);
        var rel = new top.everblue.oj.teaching.entity.ClassCourseware(); rel.setId(1L); rel.setClassId(3L);
        when(relations.getById(1L)).thenReturn(rel);
        var controller = new ClassCoursewareController(relations, mock(CoursewareService.class), membership);
        login(8, "TEACHER"); assertThrows(ForbiddenException.class, () -> controller.removeCoursewareFromClass(1L));
        verify(relations, never()).removeById(1L);
        login(7, "TEACHER"); controller.removeCoursewareFromClass(1L); verify(relations).removeById(1L);
        login(10, "STUDENT"); when(students.count(any())).thenReturn(1L);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "test"), top.everblue.oj.teaching.entity.ClassCourseware.class);
        controller.getDirectory(3L, 0L);
        var captor = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(relations).list(captor.capture());
        assertTrue(captor.getValue().getCustomSqlSegment().contains("is_visible"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(true));
    }
}
