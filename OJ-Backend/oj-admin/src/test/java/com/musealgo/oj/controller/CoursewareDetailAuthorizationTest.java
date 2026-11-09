package com.musealgo.oj.controller;

import com.musealgo.oj.api.user.UserQuery;
import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.system.entity.User;
import com.musealgo.oj.teaching.SlidevBuilderService;
import com.musealgo.oj.teaching.controller.CoursewareController;
import com.musealgo.oj.teaching.entity.Courseware;
import com.musealgo.oj.teaching.service.ClassCoursewareService;
import com.musealgo.oj.teaching.service.CoursewareService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CoursewareDetailAuthorizationTest {

    private static final String DIRECTORY = "0123456789abcdef0123456789abcdef";
    private final CoursewareService coursewareService = mock(CoursewareService.class);
    private final ClassCoursewareService classCoursewareService = mock(ClassCoursewareService.class);
    private final CoursewareController controller = new CoursewareController(coursewareService,
            classCoursewareService, mock(UserQuery.class), mock(SlidevBuilderService.class));

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authorizedPrivateDetailIssuesStrictPathScopedCookiesForBothResourceAliases() {
        Courseware courseware = privateCourseware();
        when(coursewareService.getById(88L)).thenReturn(courseware);
        authenticate(10L, "TEACHER");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);
        request.addHeader("Authorization", "Bearer valid-access-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Result<Courseware> result = controller.getDetail(88L, request, response);

        assertEquals(200, result.getCode());
        assertEquals("private, no-store", response.getHeader("Cache-Control"));
        // Assert cookie semantics rather than header attribute ordering: the mock
        // servlet response parses and serializes Set-Cookie into canonical order.
        var cookies = List.of(response.getCookies());
        assertEquals(2, cookies.size());
        assertTrue(cookies.stream().anyMatch(cookie -> ("/coursewares/" + DIRECTORY + "/").equals(cookie.getPath())));
        assertTrue(cookies.stream().anyMatch(cookie -> ("/api/coursewares/" + DIRECTORY + "/").equals(cookie.getPath())));
        cookies.forEach(cookie -> {
            assertEquals("courseware_access", cookie.getName());
            assertEquals("valid-access-token", cookie.getValue());
            assertEquals(10800, cookie.getMaxAge());
            assertTrue(cookie.isHttpOnly());
            assertTrue(cookie.getSecure());
            assertEquals("Strict", cookie.getAttribute("SameSite"));
        });
    }

    @Test
    void deniedPrivateDetailDoesNotIssueStaticResourceCredentials() {
        when(coursewareService.getById(88L)).thenReturn(privateCourseware());
        authenticate(52L, "STUDENT");
        when(classCoursewareService.isVisibleForMember(88L, 52L)).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-access-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Result<Courseware> result = controller.getDetail(88L, request, response);

        assertEquals(403, result.getCode());
        assertTrue(response.getHeaders("Set-Cookie").isEmpty());
        verify(classCoursewareService).isVisibleForMember(88L, 52L);
    }

    @Test
    void publicDetailDoesNotNeedOrIssueAStaticCredential() {
        Courseware courseware = privateCourseware();
        courseware.setPrivacy(1);
        when(coursewareService.getById(88L)).thenReturn(courseware);
        MockHttpServletResponse response = new MockHttpServletResponse();

        Result<Courseware> result = controller.getDetail(88L, new MockHttpServletRequest(), response);

        assertEquals(200, result.getCode());
        assertTrue(response.getHeaders("Set-Cookie").isEmpty());
        verifyNoInteractions(classCoursewareService);
    }

    @Test
    void updateWithoutUpdatableFieldsIsRejectedBeforeWrite() {
        when(coursewareService.getById(88L)).thenReturn(privateCourseware());
        authenticate(10L, "TEACHER");
        Courseware request = new Courseware();
        request.setId(88L);

        Result<String> result = controller.update(request);

        assertEquals(400, result.getCode());
        assertEquals("TEACHING_INPUT_INVALID", result.getErrorCode());
        org.mockito.Mockito.verify(coursewareService, org.mockito.Mockito.never())
                .updateById(org.mockito.ArgumentMatchers.any());
    }

    private static Courseware privateCourseware() {
        Courseware courseware = new Courseware();
        courseware.setId(88L);
        courseware.setTeacherId(10L);
        courseware.setPrivacy(0);
        courseware.setUrl("http://localhost:8080/coursewares/" + DIRECTORY + "/index.html");
        return courseware;
    }

    private static void authenticate(Long userId, String role) {
        User user = new User();
        user.setId(userId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
