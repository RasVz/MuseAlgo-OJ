package com.musealgo.oj.teaching.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import com.musealgo.oj.system.entity.User;
import com.musealgo.oj.teaching.entity.Courseware;
import com.musealgo.oj.teaching.service.CoursewareService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CoursewareControllerUrlValidationTest {

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void creationCannotChooseDatabaseManagedIdentityOrLifecycleFields() {
        User user = new User();
        user.setId(12L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
        CoursewareService service = mock(CoursewareService.class);
        CoursewareController controller = new CoursewareController(service, null, null, null);
        Courseware request = new Courseware();
        request.setId(999L);
        request.setTeacherId(99L);
        request.setIsDeleted(1);
        request.setCreateTime(java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
        request.setTitle("Synthetic courseware");
        request.setUrl("/coursewares/synthetic/index.html");
        when(service.save(any(Courseware.class))).thenAnswer(invocation -> {
            Courseware row = invocation.getArgument(0);
            org.junit.jupiter.api.Assertions.assertAll(
                    () -> assertNull(row.getId()),
                    () -> assertEquals(12L, row.getTeacherId()),
                    () -> assertEquals(0, row.getIsDeleted()),
                    () -> assertNull(row.getCreateTime()),
                    () -> assertEquals("Synthetic courseware", row.getTitle()),
                    () -> assertEquals("/coursewares/synthetic/index.html", row.getUrl()));
            return true;
        });
        assertEquals(200, controller.add(request).getCode());
    }

    @Test void invalidCreateAndUpdateNeverPersistTheAddress() {
        User user = new User();
        user.setId(12L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
        CoursewareService service = mock(CoursewareService.class);
        CoursewareController controller = new CoursewareController(service, null, null, null);
        Courseware request = new Courseware();
        request.setUrl("javascript:syntheticMarker");
        assertEquals(400, controller.add(request).getCode());
        verifyNoInteractions(service);

        Courseware existing = new Courseware();
        existing.setId(8L);
        existing.setTeacherId(12L);
        when(service.getById(8L)).thenReturn(existing);
        request.setId(8L);
        assertEquals(400, controller.update(request).getCode());
        verify(service, never()).updateById(any(Courseware.class));
    }

    @Test
    void acceptsHttpHttpsAndRelativeCoursewareUrls() {
        assertEquals("https://docs.qq.com/slide/example",
                CoursewareController.normalizeCoursewareUrl("https://docs.qq.com/slide/example"));
        assertEquals("HTTP://example.test/courseware",
                CoursewareController.normalizeCoursewareUrl("HTTP://example.test/courseware"));
        assertEquals("/coursewares/lesson/index.html",
                CoursewareController.normalizeCoursewareUrl("/coursewares/lesson/index.html"));
        assertEquals("coursewares/lesson/index.html",
                CoursewareController.normalizeCoursewareUrl("coursewares/lesson/index.html"));
    }

    @Test
    void rejectsActiveSchemesAndProtocolRelativeUrls() {
        for (String value : new String[]{
                "javascript:syntheticMarker",
                "data:text/html,syntheticMarker",
                "file:///etc/passwd",
                "//attacker.example/courseware",
                "///attacker.example/courseware",
                "https:\\\\attacker.example/courseware",
                "?only-a-query",
                "#only-a-fragment",
                "   "
        }) {
            assertNull(CoursewareController.normalizeCoursewareUrl(value), value);
        }
    }
}
