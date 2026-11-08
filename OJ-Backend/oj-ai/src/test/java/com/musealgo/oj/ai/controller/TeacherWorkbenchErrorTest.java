package com.musealgo.oj.ai.controller;

import com.musealgo.oj.ai.HintLadderQueryService;
import com.musealgo.oj.ai.TeacherWorkbenchService;
import com.musealgo.oj.ai.dto.AiCallSource;
import com.musealgo.oj.ai.dto.AiCompletionResult;
import com.musealgo.oj.ai.usage.AiUsageRecorder;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TeacherWorkbenchErrorTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"INVALID_RESPONSE", "RESPONSE_TOO_LARGE"})
    void invalidUpstreamPayloadIsAResponseFailure(String failureType) {
        var workbench = mock(TeacherWorkbenchService.class);
        when(workbench.teacherWorkbenchJsonDetailed(anyString(), anyString()))
                .thenReturn(AiCompletionResult.failure(AiCallSource.off(), failureType, "synthetic vendor secret"));
        var controller = new TeacherAiController(workbench, mock(AiUsageRecorder.class), mock(HintLadderQueryService.class));
        var response = controller.wbStatement(Map.of("knowledgePoint", "synthetic addition"));
        assertEquals(502, response.getCode());
        assertEquals("AI_RESPONSE_INVALID", response.getErrorCode());
        verify(workbench).teacherWorkbenchJsonDetailed(anyString(), anyString());
    }

    @Test void upstreamTimeoutPreservesRetryableReasonWithoutFormatRepairCalls() {
        var workbench = mock(TeacherWorkbenchService.class);
        when(workbench.teacherWorkbenchJsonDetailed(anyString(), anyString()))
                .thenReturn(AiCompletionResult.failure(AiCallSource.off(), "TIMEOUT", "synthetic vendor secret"));
        var controller = new TeacherAiController(workbench, mock(AiUsageRecorder.class), mock(HintLadderQueryService.class));
        var response = controller.wbStatement(Map.of("knowledgePoint", "synthetic addition"));
        assertEquals(504, response.getCode());
        assertEquals("UPSTREAM_TIMEOUT", response.getErrorCode());
        assertFalse(response.getMessage().contains("synthetic vendor secret"));
        verify(workbench).teacherWorkbenchJsonDetailed(anyString(), anyString());
    }

    @Test void brokenModelOutputIsNotReportedAsBadUserInput() {
        var workbench = mock(TeacherWorkbenchService.class);
        when(workbench.teacherWorkbenchJsonDetailed(anyString(), anyString()))
                .thenReturn(AiCompletionResult.success("broken-output", null, AiCallSource.off()));
        var controller = new TeacherAiController(workbench, mock(AiUsageRecorder.class), mock(HintLadderQueryService.class));
        var response = controller.wbStatement(Map.of("knowledgePoint", "synthetic addition"));
        assertEquals(502, response.getCode());
        assertEquals("AI_RESPONSE_INVALID", response.getErrorCode());
        verify(workbench, times(3)).teacherWorkbenchJsonDetailed(anyString(), anyString());
    }
}
