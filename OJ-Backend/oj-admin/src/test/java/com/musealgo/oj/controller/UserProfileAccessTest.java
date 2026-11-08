package com.musealgo.oj.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.musealgo.oj.common.exception.GlobalExceptionHandler;
import com.musealgo.oj.system.entity.User;
import com.musealgo.oj.system.service.UserService;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserProfileAccessTest {
    private UserController controller;
    private JdbcTemplate jdbc;
    private UserService users;
    @BeforeEach void setup() {
        SecurityContextHolder.clearContext();
        controller = new UserController();
        jdbc = mock(JdbcTemplate.class);
        users = mock(UserService.class);
        ReflectionTestUtils.setField(controller, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(controller, "userService", users);
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    @Test void failedPrivacyReadStopsBeforeFetchingProfile() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(4L)))
                .thenThrow(new DataAccessResourceFailureException("synthetic database unavailable"));
        assertThrows(DataAccessResourceFailureException.class, () -> controller.getUserInfo(4L));
        verifyNoInteractions(users);
    }
    @Test void failedPrivacyReadUsesGenericErrorResponse() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(4L)))
                .thenThrow(new DataAccessResourceFailureException("synthetic SQL secrets"));
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/user/info/4")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("系统暂时不可用，请稍后重试"));
        verifyNoInteractions(users);
    }
    @Test void anonymousCannotReadPrivateProfile() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(4L))).thenReturn(1);
        assertEquals(403, controller.getUserInfo(4L).getCode());
        verifyNoInteractions(users);
    }
    @Test void anonymousCanReadPublicProfile() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(4L))).thenReturn(0);
        User user = new User(); user.setId(4L); user.setUsername("public-student");
        when(users.getById(4L)).thenReturn(user);
        assertEquals(200, controller.getUserInfo(4L).getCode());
        verify(users).getById(4L);
    }
}
