package com.musealgo.oj.common.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import com.musealgo.oj.common.utils.JwtUtils;
import com.musealgo.oj.system.service.JwtBlacklistService;
import com.musealgo.oj.system.service.impl.UserDetailsServiceImpl;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TokenPurposeTest {
    @Test
    void refreshTokenCannotAuthenticateApiRequest() throws Exception {
        var jwt = new JwtUtils(java.util.UUID.randomUUID().toString().repeat(2));
        var users = mock(UserDetailsServiceImpl.class);
        when(users.loadUserByUsername("student")).thenReturn(
                org.springframework.security.core.userdetails.User.withUsername("student").password("x").roles("STUDENT").build());
        var filter = new JwtAuthenticationTokenFilter();
        ReflectionTestUtils.setField(filter, "jwtUtils", jwt);
        ReflectionTestUtils.setField(filter, "userDetailsService", users);
        ReflectionTestUtils.setField(filter, "jwtBlacklistService", mock(JwtBlacklistService.class));
        var request = new MockHttpServletRequest("GET", "/private");
        request.addHeader("Authorization", "Bearer " + jwt.generateRefreshToken("student", true));
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                assertNull(SecurityContextHolder.getContext().getAuthentication()));
    }
}
