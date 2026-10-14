package top.everblue.oj.ai;

import cn.hutool.json.JSONArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.everblue.oj.ai.dto.AiAskOutcome;
import top.everblue.oj.ai.gateway.AiGateway;
import top.everblue.oj.ai.gateway.PromptRegistry;
import top.everblue.oj.ai.guard.AiContestGuard;
import top.everblue.oj.ai.quota.AiQuotaService;
import top.everblue.oj.api.submission.SubmissionReviewQuery;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hint Ladder 解锁约束（seam：AiService.streamChatGuarded 公开入口）。
 * 服务端强制逐级：请求级别 > 已解锁级别时降级；成功后解锁下一级。
 */
@ExtendWith(MockitoExtension.class)
class AiServiceHintLadderTest {

    @Mock
    private AiGateway gateway;
    @Mock
    private PromptRegistry promptRegistry;
    @Mock
    private AiChatSessionService sessionService;
    @Mock
    private top.everblue.oj.ai.RagContextService ragContextService;
    @Mock
    private AiQuotaService quotaService;
    @Mock
    private AiContestGuard contestGuard;
    @Mock
    private SubmissionReviewQuery submissionReviewQuery;
    @Mock
    private TeacherWorkbenchService teacherWorkbenchService;
    @Mock
    private ReviewApplicationService reviewApplicationService;

    private AiService newService() {
        return new AiService(gateway, promptRegistry, sessionService,
                ragContextService, quotaService, contestGuard,
                teacherWorkbenchService, reviewApplicationService);
    }

    private void stubGuardsAndSuccess() {
        when(contestGuard.shouldBlock(anyLong(), anyBoolean())).thenReturn(false);
        when(quotaService.tryAcquire(anyLong(), anyBoolean())).thenReturn(true);
        when(gateway.stream(any(), any()))
                .thenReturn(new DeepSeekClient.StreamCallResult(
                        "答案", new DeepSeekClient.TokenUsage(10, 20, 30)));
    }

    @Test
    void requestedLevelAboveUnlockedIsClampedDown() {
        stubGuardsAndSuccess();
        when(sessionService.getHintLevel(1L, 10L)).thenReturn(1); // 只解锁到 L1
        List<String> chunks = new ArrayList<>();

        AiAskOutcome outcome = newService().streamChatGuarded(
                1L, 10L, "题", "描述", "code", "要 L4 题解", 4, false, chunks::add);

        assertEquals("SUCCESS", outcome.status());
        ArgumentCaptor<JSONArray> messagesCaptor = ArgumentCaptor.forClass(JSONArray.class);
        verify(gateway).stream(messagesCaptor.capture(), any());
        String prompt = messagesCaptor.getValue().toString();
        assertTrue(prompt.contains("L1 思路方向"), "请求 L4 但只解锁 L1，应降级为 L1");
        assertFalse(prompt.contains("L4 完整题解"));
        // 成功后只解锁到 L2
        verify(sessionService).unlockNextHintLevel(1L, 10L, 1);
    }

    @Test
    void requestedLevelWithinUnlockedIsAllowedAndUnlocksNext() {
        stubGuardsAndSuccess();
        when(sessionService.getHintLevel(1L, 10L)).thenReturn(2);
        List<String> chunks = new ArrayList<>();

        newService().streamChatGuarded(
                1L, 10L, "题", "描述", "code", "给伪代码", 2, false, chunks::add);

        ArgumentCaptor<JSONArray> messagesCaptor = ArgumentCaptor.forClass(JSONArray.class);
        verify(gateway).stream(messagesCaptor.capture(), any());
        assertTrue(messagesCaptor.getValue().toString().contains("L2 关键观察"));
        verify(sessionService).unlockNextHintLevel(1L, 10L, 2);
    }

    @Test
    void defaultLevelKeepsOriginalModeWithoutUnlock() {
        stubGuardsAndSuccess();
        when(sessionService.getHintLevel(1L, 10L)).thenReturn(1);
        List<String> chunks = new ArrayList<>();

        newService().streamChatGuarded(
                1L, 10L, "题", "描述", "code", "普通提问", 0, false, chunks::add);

        ArgumentCaptor<JSONArray> messagesCaptor = ArgumentCaptor.forClass(JSONArray.class);
        verify(gateway).stream(messagesCaptor.capture(), any());
        assertFalse(messagesCaptor.getValue().toString().contains("【本轮引导级别"));
        verify(sessionService, never()).unlockNextHintLevel(anyLong(), anyLong(), anyInt());
    }
}
