package com.musealgo.oj.common.filter;

import com.musealgo.oj.common.utils.JwtUtils;
import com.musealgo.oj.system.entity.LoginUser;
import com.musealgo.oj.system.entity.User;
import com.musealgo.oj.system.service.JwtBlacklistService;
import com.musealgo.oj.system.service.impl.UserDetailsServiceImpl;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JwtAuthenticationTokenFilterTest {

    private final UserDetailsServiceImpl userDetailsService = mock(UserDetailsServiceImpl.class);
    private final JwtUtils jwtUtils = mock(JwtUtils.class);
    private final JwtBlacklistService blacklistService = mock(JwtBlacklistService.class);
    private final JwtAuthenticationTokenFilter filter = new JwtAuthenticationTokenFilter();

    JwtAuthenticationTokenFilterTest() {
        ReflectionTestUtils.setField(filter, "userDetailsService", userDetailsService);
        ReflectionTestUtils.setField(filter, "jwtUtils", jwtUtils);
        ReflectionTestUtils.setField(filter, "jwtBlacklistService", blacklistService);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsAccessTokenIssuedBeforePasswordChange() throws Exception {
        LoginUser current = currentUser(9L, 3L);
        when(jwtUtils.extractUsername("old-access")).thenReturn("student");
        when(userDetailsService.loadUserByUsername("student")).thenReturn(current);
        when(jwtUtils.validateAccessToken("old-access", "student")).thenReturn(true);
        when(jwtUtils.hasCurrentCredentialVersion("old-access", 3L)).thenReturn(false);

        boolean[] authenticatedDuringRequest = {false};
        runFilter("old-access", (request, response) -> authenticatedDuringRequest[0] =
                SecurityContextHolder.getContext().getAuthentication() != null);

        assertFalse(authenticatedDuringRequest[0]);
        verifyNoInteractions(blacklistService);
    }

    @Test
    void acceptsAccessTokenAtCurrentCredentialVersion() throws Exception {
        LoginUser current = currentUser(9L, 3L);
        when(jwtUtils.extractUsername("current-access")).thenReturn("student");
        when(userDetailsService.loadUserByUsername("student")).thenReturn(current);
        when(jwtUtils.validateAccessToken("current-access", "student")).thenReturn(true);
        when(jwtUtils.hasCurrentCredentialVersion("current-access", 3L)).thenReturn(true);
        when(jwtUtils.extractJti("current-access")).thenReturn("current-jti");
        when(blacklistService.isRevoked("current-jti")).thenReturn(false);

        boolean[] authenticatedDuringRequest = {false};
        runFilter("current-access", (request, response) -> authenticatedDuringRequest[0] =
                SecurityContextHolder.getContext().getAuthentication() != null);

        assertTrue(authenticatedDuringRequest[0]);
        verify(blacklistService).isRevoked("current-jti");
    }

    @Test
    void acceptsScopedCoursewareCookieOnItsOutputPath() throws Exception {
        LoginUser current = currentUser(9L, 3L);
        when(jwtUtils.extractUsername("courseware-token")).thenReturn("student");
        when(userDetailsService.loadUserByUsername("student")).thenReturn(current);
        when(jwtUtils.validateAccessToken("courseware-token", "student")).thenReturn(true);
        when(jwtUtils.hasCurrentCredentialVersion("courseware-token", 3L)).thenReturn(true);
        when(jwtUtils.extractJti("courseware-token")).thenReturn("courseware-jti");
        when(blacklistService.isRevoked("courseware-jti")).thenReturn(false);

        boolean[] authenticatedDuringRequest = {false};
        runFilter(null, "/coursewares/0123456789abcdef0123456789abcdef/index.html",
                new Cookie("courseware_access", "courseware-token"), (request, response) ->
                        authenticatedDuringRequest[0] = SecurityContextHolder.getContext().getAuthentication() != null);

        assertTrue(authenticatedDuringRequest[0]);
    }

    @Test
    void doesNotAcceptCoursewareCookieOnOrdinaryApiPath() throws Exception {
        runFilter(null, "/user/profile", new Cookie("courseware_access", "courseware-token"),
                (request, response) -> assertFalse(SecurityContextHolder.getContext().getAuthentication() != null));

        verifyNoInteractions(jwtUtils, userDetailsService, blacklistService);
    }

    @Test
    void revokedCoursewareCookieRemainsRejected() throws Exception {
        LoginUser current = currentUser(9L, 3L);
        when(jwtUtils.extractUsername("revoked-courseware-token")).thenReturn("student");
        when(userDetailsService.loadUserByUsername("student")).thenReturn(current);
        when(jwtUtils.validateAccessToken("revoked-courseware-token", "student")).thenReturn(true);
        when(jwtUtils.hasCurrentCredentialVersion("revoked-courseware-token", 3L)).thenReturn(true);
        when(jwtUtils.extractJti("revoked-courseware-token")).thenReturn("revoked-jti");
        when(blacklistService.isRevoked("revoked-jti")).thenReturn(true);

        boolean[] authenticatedDuringRequest = {false};
        runFilter(null, "/api/coursewares/0123456789abcdef0123456789abcdef/assets/app.js",
                new Cookie("courseware_access", "revoked-courseware-token"), (request, response) ->
                        authenticatedDuringRequest[0] = SecurityContextHolder.getContext().getAuthentication() != null);

        assertFalse(authenticatedDuringRequest[0]);
        verify(blacklistService).isRevoked("revoked-jti");
    }

    @Test
    void dependencyFailureReturns503WithoutReachingProtectedController() throws Exception {
        when(jwtUtils.extractUsername("valid-access")).thenReturn("student");
        when(userDetailsService.loadUserByUsername("student")).thenThrow(
                new org.springframework.dao.DataAccessResourceFailureException("SELECT private_password FROM secret"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/private");
        request.addHeader("Authorization", "Bearer valid-access");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] reached = {false};
        filter.doFilter(request, response, (req, res) -> reached[0] = true);
        org.junit.jupiter.api.Assertions.assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("AUTH_DEPENDENCY_UNAVAILABLE"));
        assertFalse(response.getContentAsString().contains("SELECT"));
        assertFalse(reached[0]);
        org.junit.jupiter.api.Assertions.assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    private void runFilter(String token, FilterChain chain) throws Exception {
        runFilter(token, "/private", null, chain);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"connection,503,AUTH_DEPENDENCY_UNAVAILABLE", "sql,500,INTERNAL_ERROR"})
    void classifiesWrappedDatabaseFailuresWithoutAuthenticating(String kind, int status, String reason) throws Exception {
        when(jwtUtils.extractUsername("valid-access")).thenReturn("student");
        RuntimeException cause = kind.equals("connection")
                ? new org.springframework.jdbc.CannotGetJdbcConnectionException("synthetic private SQL")
                : new org.springframework.dao.InvalidDataAccessResourceUsageException("synthetic private SQL");
        when(userDetailsService.loadUserByUsername("student")).thenThrow(new org.apache.ibatis.exceptions.PersistenceException(cause));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/private");
        request.addHeader("Authorization", "Bearer valid-access");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> org.junit.jupiter.api.Assertions.fail("Must not authorize"));
        org.junit.jupiter.api.Assertions.assertEquals(status, response.getStatus());
        assertTrue(response.getContentAsString().contains(reason));
        assertFalse(response.getContentAsString().contains("synthetic private SQL"));
        org.junit.jupiter.api.Assertions.assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    private void runFilter(String token, String path, Cookie cookie, FilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (token != null) request.addHeader("Authorization", "Bearer " + token);
        if (cookie != null) request.setCookies(cookie);
        filter.doFilter(request, new MockHttpServletResponse(), chain);
    }

    private LoginUser currentUser(Long id, long credentialVersion) {
        User user = new User();
        user.setId(id);
        user.setUsername("student");
        user.setCredentialVersion(credentialVersion);
        user.setIsBanned((byte) 0);
        return new LoginUser(user, List.of());
    }
}
