package com.musealgo.oj.ai.controller;

import com.musealgo.oj.ai.*;
import com.musealgo.oj.ai.dto.AiAskOutcome;
import com.musealgo.oj.ai.quota.AiQuotaService;
import com.musealgo.oj.ai.usage.AiUsageRecorder;
import com.musealgo.oj.common.exception.GlobalExceptionHandler;
import com.musealgo.oj.common.security.AuthenticatedUser;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class AiStreamErrorContractTest {
    record Actor(Long id) implements AuthenticatedUser { public Long getId() { return id; } }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest
    @CsvSource({"FAIL,AI_UPSTREAM_FAILED", "QUOTA_REJECTED,AI_QUOTA_EXCEEDED", "CONTEST_BLOCKED,AI_CONTEST_BLOCKED", "SUCCESS,done"})
    void failedOrBlockedOutcomeCannotSendSuccessDone(String outcome, String expected) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new Actor(7L), null, List.of()));
        AiService ai = mock(AiService.class);
        when(ai.streamChatGuarded(anyLong(), any(), any(), anyBoolean(), any()))
                .thenReturn(new AiAskOutcome(outcome, "回答", null, "", null));
        ExecutorService executor = mock(ExecutorService.class);
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(0)).run(); return null; }).when(executor).execute(any());
        var controller = new AiController(ai, mock(AiQuotaService.class), mock(AiUsageRecorder.class), mock(AiChatSessionService.class), executor, mock(com.musealgo.oj.api.problem.ProblemTutoringQuery.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        var initial = mvc.perform(post("/ai/ask/stream").contentType(MediaType.APPLICATION_JSON)
                .content("{\"problemId\":1,\"problemTitle\":\"测试\",\"studentQuestion\":\"如何思考\"}")).andReturn();
        var result = mvc.perform(asyncDispatch(initial)).andReturn();
        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        if ("SUCCESS".equals(outcome)) assertTrue(body.contains("event:done"), body);
        else {
            assertTrue(body.contains("event:app-error"), body);
            assertTrue(body.contains(expected), body);
            assertFalse(body.contains("event:done"), body);
        }
    }
}
