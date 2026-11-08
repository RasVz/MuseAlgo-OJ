package com.musealgo.oj.common.config;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.musealgo.oj.common.security.AuthenticatedUser;
import com.musealgo.oj.teaching.entity.Courseware;
import com.musealgo.oj.teaching.service.ClassCoursewareService;
import com.musealgo.oj.teaching.service.CoursewareService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CoursewareStaticResourceInterceptorTest {

    private static final String DIRECTORY = "0123456789abcdef0123456789abcdef";

    @Mock private CoursewareService coursewareService;
    @Mock private ClassCoursewareService classCoursewareService;

    private CoursewareStaticResourceInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new CoursewareStaticResourceInterceptor(coursewareService, classCoursewareService);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void publicCoursewareRemainsAvailableWithoutAuthenticationOnBothPrefixes() throws Exception {
        Courseware courseware = courseware(1);
        when(coursewareService.list(any(Wrapper.class))).thenReturn(List.of(courseware));

        assertTrue(intercept("/coursewares/" + DIRECTORY + "/index.html", new MockHttpServletResponse()));
        assertTrue(intercept("/api/coursewares/" + DIRECTORY + "/assets/app.js", new MockHttpServletResponse()));

        verify(classCoursewareService, never()).isVisibleForMember(any(), any());
    }

    @Test
    void privateCoursewareRequiresAuthenticationAndDisablesCaching() throws Exception {
        when(coursewareService.list(any(Wrapper.class))).thenReturn(List.of(courseware(0)));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(intercept("/coursewares/" + DIRECTORY + "/index.html", response));

        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        assertEquals("private, no-store", response.getHeader("Cache-Control"));
    }

    @Test
    void privateCoursewareChecksCurrentVisibleMembershipForEveryAsset() throws Exception {
        Courseware courseware = courseware(0);
        when(coursewareService.list(any(Wrapper.class))).thenReturn(List.of(courseware));
        authenticate(52L, "ROLE_STUDENT");
        when(classCoursewareService.isVisibleForMember(courseware.getId(), 52L)).thenReturn(true);

        assertTrue(intercept("/api/coursewares/" + DIRECTORY + "/assets/app.js", new MockHttpServletResponse()));

        verify(classCoursewareService).isVisibleForMember(courseware.getId(), 52L);
    }

    @Test
    void removedClassMemberIsDeniedForPreviouslyKnownStaticUrl() throws Exception {
        Courseware courseware = courseware(0);
        when(coursewareService.list(any(Wrapper.class))).thenReturn(List.of(courseware));
        authenticate(52L, "ROLE_STUDENT");
        when(classCoursewareService.isVisibleForMember(courseware.getId(), 52L)).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(intercept("/coursewares/" + DIRECTORY + "/index.html", response));

        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
        verify(classCoursewareService).isVisibleForMember(courseware.getId(), 52L);
    }

    @Test
    void ownerCanReadPrivateCoursewareWithoutAClassBinding() throws Exception {
        Courseware courseware = courseware(0);
        courseware.setTeacherId(52L);
        when(coursewareService.list(any(Wrapper.class))).thenReturn(List.of(courseware));
        authenticate(52L, "ROLE_TEACHER");

        assertTrue(intercept("/coursewares/" + DIRECTORY + "/index.html", new MockHttpServletResponse()));

        verify(classCoursewareService, never()).isVisibleForMember(any(), any());
    }

    @Test
    void unregisteredOrMalformedOutputDirectoryIsNotServed() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(intercept("/api/coursewares/../private/index.html", response));
        assertEquals(HttpServletResponse.SC_NOT_FOUND, response.getStatus());
    }

    @Test
    void rawAndEncodedTraversalAreRejectedBeforeAVisibleDirectoryCanAuthorizeAnotherPath() throws Exception {
        for (String suffix : List.of("../private/index.html", "%2e%2e/private/index.html",
                "%2fprivate/index.html", "%252e%252e/private/index.html")) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertFalse(intercept("/coursewares/" + DIRECTORY + "/" + suffix, response), suffix);
            assertEquals(HttpServletResponse.SC_NOT_FOUND, response.getStatus(), suffix);
        }

        verify(coursewareService, never()).list(any(Wrapper.class));
    }

    private boolean intercept(String path, MockHttpServletResponse response) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(path);
        return interceptor.preHandle(request, response, new Object());
    }

    private static Courseware courseware(int privacy) {
        Courseware courseware = new Courseware();
        courseware.setId(88L);
        courseware.setTeacherId(10L);
        courseware.setPrivacy(privacy);
        courseware.setUrl("http://localhost:8080/coursewares/" + DIRECTORY + "/index.html");
        return courseware;
    }

    private static void authenticate(Long userId, String authority) {
        AuthenticatedUser principal = () -> userId;
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority(authority))));
    }
}
