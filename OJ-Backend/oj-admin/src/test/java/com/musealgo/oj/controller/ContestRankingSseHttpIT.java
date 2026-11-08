package com.musealgo.oj.controller;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.musealgo.oj.common.config.SecurityAuthorizationExceptionHandler;
import com.musealgo.oj.common.config.SecurityConfig;
import com.musealgo.oj.common.exception.GlobalExceptionHandler;
import com.musealgo.oj.common.filter.JwtAuthenticationTokenFilter;
import com.musealgo.oj.common.utils.JwtUtils;
import com.musealgo.oj.contest.controller.ContestRankingSseController;
import com.musealgo.oj.contest.entity.Contest;
import com.musealgo.oj.contest.service.ContestService;
import com.musealgo.oj.contest.sse.ContestRankBoardType;
import com.musealgo.oj.contest.sse.SseRegistry;
import com.musealgo.oj.system.entity.LoginUser;
import com.musealgo.oj.system.entity.User;
import com.musealgo.oj.system.service.JwtBlacklistService;
import com.musealgo.oj.system.service.impl.UserDetailsServiceImpl;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC/security regression for SSE authentication and OI visibility policy. The
 * Accept header exercises the negotiation that used to turn the OI student's
 * JSON global exception body into HTTP 500.
 */
@WebMvcTest(controllers = ContestRankingSseController.class)
@ContextConfiguration(classes = {ContestRankingSseController.class, SecurityConfig.class,
        JwtAuthenticationTokenFilter.class, SecurityAuthorizationExceptionHandler.class,
        GlobalExceptionHandler.class})
class ContestRankingSseHttpIT {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ContestService contestService;

    @MockBean
    private SseRegistry sseRegistry;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @MockBean
    private JwtUtils jwtUtils;

    @MockBean
    private JwtBlacklistService jwtBlacklistService;

    @Test
    void anonymousRequestStillGets401() throws Exception {
        mockMvc.perform(get("/contest/{contestId}/ranking/stream", 90210L)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(contestService, sseRegistry);
    }

    @Test
    void authenticatedStudentGetsBodyless403ForRunningOiStream() throws Exception {
        stubAuthenticatedUser("rank-student-token", "rank-student", 90210L, "ROLE_STUDENT");
        stubContest(90210L, "OI", true);

        var response = mockMvc.perform(streamRequest(90210L, "rank-student-token"))
                .andExpect(status().isForbidden())
                .andReturn()
                .getResponse();
        var globalResponse = mockMvc.perform(globalStreamRequest(90210L, "rank-student-token"))
                .andExpect(status().isForbidden())
                .andReturn()
                .getResponse();

        assertEquals(0, response.getContentAsByteArray().length,
                "the rejection must not be encoded as an SSE frame or JSON body");
        assertEquals(0, globalResponse.getContentAsByteArray().length,
                "the global rejection must also be bodyless during a running OI contest");
        verifyNoInteractions(sseRegistry);
    }

    @Test
    void authenticatedStudentCanSubscribeToAcmStream() throws Exception {
        stubAuthenticatedUser("rank-acm-student-token", "rank-acm-student", 90211L, "ROLE_STUDENT");
        stubContest(90211L, "ACM", true);

        assertConnectedStream(90211L, "rank-acm-student-token",
                "/contest/{contestId}/ranking/stream", ContestRankBoardType.OFFICIAL);
    }

    @Test
    void authenticatedAdminCanSubscribeToRunningOiStream() throws Exception {
        stubAuthenticatedUser("rank-oi-admin-token", "rank-oi-admin", 90212L, "ROLE_ADMIN");
        stubContest(90212L, "OI", true);

        assertConnectedStream(90212L, "rank-oi-admin-token",
                "/contest/{contestId}/ranking/global/stream", ContestRankBoardType.GLOBAL);
    }

    private void stubAuthenticatedUser(String token, String username, long userId, String role) {
        String jti = username + "-jti";
        User user = new User();
        user.setId(userId);
        user.setUsername(username);
        user.setRole((byte) ("ROLE_ADMIN".equals(role) ? 3 : 1));
        LoginUser principal = new LoginUser(user, List.of(new SimpleGrantedAuthority(role)));

        when(jwtUtils.extractUsername(token)).thenReturn(username);
        when(userDetailsService.loadUserByUsername(username)).thenReturn(principal);
        when(jwtUtils.validateAccessToken(token, username)).thenReturn(true);
        when(jwtUtils.extractJti(token)).thenReturn(jti);
        when(jwtBlacklistService.isRevoked(jti)).thenReturn(false);
    }

    private void stubContest(long contestId, String mode, boolean inProgress) {
        Contest contest = new Contest();
        contest.setMode(mode);
        if (inProgress) {
            contest.setStartTime(LocalDateTime.now().minusMinutes(5));
            contest.setEndTime(LocalDateTime.now().plusMinutes(5));
        }
        when(contestService.getById(contestId)).thenReturn(contest);
    }

    private MockHttpServletRequestBuilder streamRequest(long contestId, String token) {
        return get("/contest/{contestId}/ranking/stream", contestId)
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.TEXT_EVENT_STREAM);
    }

    private MockHttpServletRequestBuilder globalStreamRequest(long contestId, String token) {
        return get("/contest/{contestId}/ranking/global/stream", contestId)
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.TEXT_EVENT_STREAM);
    }

    private void assertConnectedStream(long contestId, String token, String path,
                                       ContestRankBoardType boardType) throws Exception {
        MockHttpServletRequestBuilder requestBuilder = get(path, contestId)
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.TEXT_EVENT_STREAM);
        MvcResult result = mockMvc.perform(requestBuilder)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        ArgumentCaptor<SseEmitter> emitterCaptor = ArgumentCaptor.forClass(SseEmitter.class);
        verify(sseRegistry).register(eq(contestId), eq(boardType), emitterCaptor.capture());
        try {
            String responseBody = result.getResponse().getContentAsString();
            boolean connectedEventPresent = Arrays.stream(responseBody.split("\\R"))
                    .map(String::trim)
                    .filter(line -> line.startsWith("event:"))
                    .map(line -> line.substring("event:".length()).trim())
                    .anyMatch("connected"::equals);
            assertTrue(connectedEventPresent,
                    "SSE response should contain an event:connected frame (optional field whitespace allowed), body="
                            + responseBody);
        } finally {
            emitterCaptor.getValue().complete();
        }
    }
}
