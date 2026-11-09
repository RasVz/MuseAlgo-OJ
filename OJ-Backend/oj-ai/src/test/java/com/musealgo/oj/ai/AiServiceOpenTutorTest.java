package com.musealgo.oj.ai;
import cn.hutool.json.JSONArray;
import com.musealgo.oj.ai.dto.*;
import com.musealgo.oj.ai.gateway.*;
import com.musealgo.oj.ai.guard.AiContestGuard;
import com.musealgo.oj.ai.quota.AiQuotaService;
import com.musealgo.oj.ai.tutor.TutorMessageBuilder;
import com.musealgo.oj.api.problem.ProblemTutoringQuery;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class AiServiceOpenTutorTest {
    final AiGateway gateway = mock(AiGateway.class);
    final AiChatSessionService sessions = mock(AiChatSessionService.class);
    final AiQuotaService quota = mock(AiQuotaService.class);
    final TutorMessageBuilder builder = new TutorMessageBuilder(mock(PromptRegistry.class));
    final AiService service = new AiService(gateway, sessions, mock(RagContextService.class), quota,
            new AiContestGuard(), mock(TeacherWorkbenchService.class), mock(ReviewApplicationService.class), builder);
    AiAskRequest request() {
        var request = new AiAskRequest(); request.setProblemId(10L); request.setStudentCode("int x = 1;");
        request.setStudentQuestion("请给完整代码"); request.setHelpDirection(AiTeachingPreferences.HelpDirection.FULL_SOLUTION);
        return request;
    }
    @Test void firstFullSolutionIsAllowedAndNeverReadsOrWritesUnlocks() {
        when(quota.tryAcquire(1L, false)).thenReturn(true);
        when(gateway.stream(any(), any())).thenReturn(AiStreamResult.success("参考实现", new AiTokenUsage(10,20,30), null, true));
        var outcome = service.streamChatGuarded(1L, request(), new ProblemTutoringQuery.Context(10L,"题","正式题面"), false, x -> {});
        assertEquals("SUCCESS", outcome.status());
        var capture = org.mockito.ArgumentCaptor.forClass(JSONArray.class); verify(gateway).stream(capture.capture(), any());
        var messages = capture.getValue();
        assertTrue(messages.getJSONObject(0).getStr("content").contains("首次即可请求"));
        assertFalse(messages.getJSONObject(0).getStr("content").contains("int x"));
        assertTrue(messages.getJSONObject(messages.size()-1).getStr("content").contains("正式题面"));
        verify(sessions, never()).getHintLevel(anyLong(), anyLong());
        verify(sessions, never()).unlockNextHintLevel(anyLong(), anyLong(), anyInt());
        verify(sessions).appendTurn(1L,10L,"请给完整代码","参考实现");
    }
    @Test void contestContextBlocksEvenPrivilegedUsersWithoutQuotaOrModel() {
        var request = request(); request.setContestId(99L);
        assertEquals("CONTEST_BLOCKED", service.streamChatGuarded(1L,request,null,true,x -> {}).status());
        verifyNoInteractions(gateway, quota, sessions);
    }
    @Test void quotaRejectsWithoutCallingModel() {
        assertEquals("QUOTA_REJECTED",service.streamChatGuarded(1L,request(),null,false,x -> {}).status());
        verifyNoInteractions(gateway,sessions);
    }
    @Test void cancelledAndPartialFailureNeverPersistAsCompleteHistory() {
        when(quota.tryAcquire(1L,false)).thenReturn(true);
        when(gateway.stream(any(),any())).thenReturn(new AiStreamResult("片段",null,null,false,true,"CANCELLED",null));
        var outcome=service.streamChatGuarded(1L,request(),new ProblemTutoringQuery.Context(10L,"题","题面"),false,x -> {});
        assertEquals("CANCELLED",outcome.status()); assertEquals("片段",outcome.answer());
        verify(sessions,never()).appendTurn(any(),any(),any(),any());
    }
}
