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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 代码复盘（seam：ReviewApplicationService.reviewSubmissionStream，2026-08-15 拆分后实测目标）。
 * 验证：评测特征进 prompt、比赛拦截、配额拒绝、成功路径。
 */
@ExtendWith(MockitoExtension.class)
class AiServiceReviewTest {

    @Mock
    private AiGateway gateway;
    @Mock
    private PromptRegistry promptRegistry;
    @Mock
    private AiQuotaService quotaService;
    @Mock
    private AiContestGuard contestGuard;
    @Mock
    private SubmissionReviewQuery submissionReviewQuery;

    private ReviewApplicationService newService() {
        return new ReviewApplicationService(gateway, promptRegistry,
                quotaService, contestGuard, submissionReviewQuery);
    }

    private SubmissionReviewQuery.SubmissionReviewData waSubmission() {
        return new SubmissionReviewQuery.SubmissionReviewData(
                1L, 10L, 100L, (byte) 2, "cpp",
                "int main(){ int a; return 0; }", 0, 12, 1024,
                null, null,
                List.of(new SubmissionReviewQuery.CaseStatusCount(1, 2),
                        new SubmissionReviewQuery.CaseStatusCount(2, 1)));
    }

    @Test
    void reviewPromptContainsCodeAndFailureStats() {
        when(submissionReviewQuery.findById(1L))
                .thenReturn(Optional.of(waSubmission()));
        when(contestGuard.shouldBlock(anyLong(), anyBoolean())).thenReturn(false);
        when(quotaService.tryAcquire(anyLong(), anyBoolean())).thenReturn(true);
        when(gateway.stream(any(), any()))
                .thenReturn(new DeepSeekClient.StreamCallResult(
                        "第二组用例越界了，检查数组下标。", null));
        List<String> chunks = new ArrayList<>();

        AiAskOutcome outcome = newService().reviewSubmissionStream(10L, 1L, false, chunks::add);

        assertEquals("SUCCESS", outcome.status());
        ArgumentCaptor<JSONArray> messagesCaptor = ArgumentCaptor.forClass(JSONArray.class);
        verify(gateway).stream(messagesCaptor.capture(), any());
        String prompt = messagesCaptor.getValue().toString();
        assertTrue(prompt.contains("WA×1"), "评测特征应包含用例状态计数");
        assertTrue(prompt.contains("AC×2"));
        assertTrue(prompt.contains("int main()"), "学生代码应进 prompt");
        assertFalse(prompt.contains("测试点原文"));
    }

    @Test
    void reviewIsBlockedDuringContest() {
        when(submissionReviewQuery.findById(1L))
                .thenReturn(Optional.of(waSubmission()));
        when(contestGuard.shouldBlock(anyLong(), anyBoolean())).thenReturn(true);
        List<String> chunks = new ArrayList<>();

        AiAskOutcome outcome = newService().reviewSubmissionStream(10L, 1L, false, chunks::add);

        assertEquals("CONTEST_BLOCKED", outcome.status());
        verify(gateway, org.mockito.Mockito.never()).stream(any(), any());
    }

    @Test
    void reviewRespectsQuota() {
        when(submissionReviewQuery.findById(1L))
                .thenReturn(Optional.of(waSubmission()));
        when(contestGuard.shouldBlock(anyLong(), anyBoolean())).thenReturn(false);
        when(quotaService.tryAcquire(anyLong(), anyBoolean())).thenReturn(false);
        List<String> chunks = new ArrayList<>();

        AiAskOutcome outcome = newService().reviewSubmissionStream(10L, 1L, false, chunks::add);

        assertEquals("QUOTA_REJECTED", outcome.status());
        verify(gateway, org.mockito.Mockito.never()).stream(any(), any());
    }

    @Test
    void missingSubmissionFails() {
        when(submissionReviewQuery.findById(99L)).thenReturn(Optional.empty());
        List<String> chunks = new ArrayList<>();

        AiAskOutcome outcome = newService().reviewSubmissionStream(1L, 99L, false, chunks::add);

        assertEquals("FAIL", outcome.status());
        assertTrue(chunks.stream().anyMatch(c -> c.contains("稍后再试")));
    }

    @Test
    void userCannotReviewAnotherUsersSubmission() {
        when(submissionReviewQuery.findById(1L))
                .thenReturn(Optional.of(waSubmission()));
        List<String> chunks = new ArrayList<>();

        AiAskOutcome outcome = newService().reviewSubmissionStream(20L, 1L, false, chunks::add);

        assertEquals("FAIL", outcome.status());
        assertTrue(chunks.stream().anyMatch(c -> c.contains("无权")));
        verify(gateway, org.mockito.Mockito.never()).stream(any(), any());
        verify(quotaService, org.mockito.Mockito.never()).tryAcquire(anyLong(), anyBoolean());
    }
}
