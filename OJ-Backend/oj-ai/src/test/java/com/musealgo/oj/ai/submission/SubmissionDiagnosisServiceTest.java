package com.musealgo.oj.ai.submission;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.musealgo.oj.ai.dto.AiCallSource;
import com.musealgo.oj.ai.dto.AiStreamResult;
import com.musealgo.oj.ai.dto.AiTokenUsage;
import com.musealgo.oj.ai.error.AiErrorCode;
import com.musealgo.oj.ai.gateway.AiGateway;
import com.musealgo.oj.ai.gateway.PromptRegistry;
import com.musealgo.oj.ai.guard.AiContestGuard;
import com.musealgo.oj.ai.mapper.PromptTemplateMapper;
import com.musealgo.oj.ai.mapper.SubmissionDiagnosisMapper;
import com.musealgo.oj.ai.quota.AiQuotaService;
import com.musealgo.oj.ai.usage.AiUsageRecorder;
import com.musealgo.oj.api.problem.ProblemTutoringQuery;
import com.musealgo.oj.api.submission.SubmissionReviewQuery;
import com.musealgo.oj.common.exception.BusinessException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SubmissionDiagnosisServiceTest {
    final SubmissionDiagnosisMapper mapper = mock(SubmissionDiagnosisMapper.class);
    final SubmissionReviewQuery submissions = mock(SubmissionReviewQuery.class);
    final ProblemTutoringQuery problems = mock(ProblemTutoringQuery.class);
    final AiGateway gateway = mock(AiGateway.class);
    final AiQuotaService quota = mock(AiQuotaService.class);
    final AiUsageRecorder usage = mock(AiUsageRecorder.class);
    final SubmissionDiagnosisPrompts prompts = new SubmissionDiagnosisPrompts(
            new PromptRegistry(mock(PromptTemplateMapper.class)));
    final SubmissionDiagnosisService service = new SubmissionDiagnosisService(
            mapper, submissions, problems, prompts, gateway, new AiContestGuard(), quota, usage);

    @BeforeAll
    static void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "diagnosis-test"),
                SubmissionDiagnosis.class);
    }

    private void own(byte status, Long contestId, String code, Long examAttemptId) {
        when(submissions.findById(1L)).thenReturn(Optional.of(new SubmissionReviewQuery.SubmissionReviewData(
                1L, 10L, 100L, status, "cpp", code, 0, 12, 1024, "error",
                contestId, List.of(new SubmissionReviewQuery.CaseStatusCount(2, 1)),
                examAttemptId)));
        when(problems.load(100L)).thenReturn(new ProblemTutoringQuery.Context(100L, "求和", "正式题面"));
    }

    @Test
    void promptContainsStatusStatementCodeAndShortContract() {
        own((byte) 1, null, "int main(){}", null);
        SubmissionDiagnosisService.Prepared prepared = service.prepare(10L, 1L);
        String system = prepared.messages().getJSONObject(0).getStr("content");
        String user = prepared.messages().getJSONObject(1).getStr("content");
        assertTrue(system.contains("150–300 字"));
        assertTrue(system.contains("【结论】【依据】【下一步】"));
        assertTrue(system.contains("不输出完整题解"));
        assertTrue(user.contains("状态: AC"));
        assertTrue(user.contains("正式题面"));
        assertTrue(user.contains("int main(){}"));
        assertTrue(user.contains("WA×1"));
        assertTrue(user.contains("本次评测已通过"));
    }

    @Test
    void onlyOwnerCanPrepareOrRead() {
        own((byte) 2, null, "int main(){}", null);
        assertEquals("ACCESS_DENIED", assertThrows(BusinessException.class, () -> service.prepare(20L, 1L))
                .getErrorCode().key());
        assertEquals("ACCESS_DENIED", assertThrows(BusinessException.class, () -> service.view(20L, 1L))
                .getErrorCode().key());
        assertEquals("AI_DIAGNOSIS_SUBMISSION_NOT_FOUND",
                assertThrows(BusinessException.class, () -> service.prepare(10L, 2L)).getErrorCode().key());
    }

    @Test
    void examContestPendingAndEmptyCodeAreRejected() {
        own((byte) 2, null, "int main(){}", 88L);
        assertEquals("AI_DIAGNOSIS_EXAM_BLOCKED", assertThrows(BusinessException.class,
                () -> service.prepare(10L, 1L)).getErrorCode().key());

        own((byte) 2, 99L, "int main(){}", null);
        assertEquals("AI_CONTEST_BLOCKED", assertThrows(BusinessException.class,
                () -> service.prepare(10L, 1L)).getErrorCode().key());

        own((byte) 0, null, "int main(){}", null);
        assertEquals("AI_STATE_CONFLICT", assertThrows(BusinessException.class,
                () -> service.prepare(10L, 1L)).getErrorCode().key());

        own((byte) 2, null, "  ", null);
        assertEquals("AI_STATE_CONFLICT", assertThrows(BusinessException.class,
                () -> service.prepare(10L, 1L)).getErrorCode().key());
    }

    @Test
    void claimIsOneTimeAndAllowsRetryOnlyAfterFailure() {
        own((byte) 2, null, "int main(){}", null);
        when(quota.tryAcquire(10L, false)).thenReturn(true);
        SubmissionDiagnosis succeeded = new SubmissionDiagnosis();
        succeeded.setSubmissionId(1L);
        succeeded.setUserId(10L);
        succeeded.setStatus(SubmissionDiagnosis.SUCCEEDED);
        when(mapper.selectById(1L)).thenReturn(succeeded);
        assertEquals("AI_DIAGNOSIS_ALREADY_EXISTS", assertThrows(BusinessException.class,
                () -> service.claim(service.prepare(10L, 1L), false)).getErrorCode().key());
        verify(mapper, never()).insert(any(SubmissionDiagnosis.class));

        SubmissionDiagnosis running = new SubmissionDiagnosis();
        running.setSubmissionId(1L);
        running.setUserId(10L);
        running.setStatus(SubmissionDiagnosis.RUNNING);
        running.setStartTime(LocalDateTime.now());
        when(mapper.selectById(1L)).thenReturn(running);
        assertEquals("AI_DIAGNOSIS_IN_PROGRESS", assertThrows(BusinessException.class,
                () -> service.claim(service.prepare(10L, 1L), false)).getErrorCode().key());

        SubmissionDiagnosis failed = new SubmissionDiagnosis();
        failed.setSubmissionId(1L);
        failed.setUserId(10L);
        failed.setStatus(SubmissionDiagnosis.FAILED);
        when(mapper.selectById(1L)).thenReturn(failed);
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        String execution = service.claim(service.prepare(10L, 1L), false);
        assertNotNull(execution);
        verify(mapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    void successfulFinishPersistsAndConsumesQuotaOnce() {
        own((byte) 2, null, "int main(){}", null);
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        SubmissionDiagnosisService.Prepared prepared = service.prepare(10L, 1L);
        AiStreamResult result = AiStreamResult.success("【结论】边界错误",
                new AiTokenUsage(10, 5, 15), AiCallSource.legacy("synthetic"), true);
        assertTrue(service.finish(prepared, "execution", result, 120L, false));
        verify(quota).consume(10L, 15, false);
        verify(usage).record(eq(10L), eq(100L), eq("DIAGNOSIS"), eq(10), eq(5), eq(false), eq(120L),
                eq("SUCCESS"), isNull(), any(AiCallSource.class));
    }

    @Test
    void failedFinishDoesNotConsumeQuotaAndRecordsFailure() {
        own((byte) 2, null, "int main(){}", null);
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        SubmissionDiagnosisService.Prepared prepared = service.prepare(10L, 1L);
        AiStreamResult result = AiStreamResult.failure(null, false, "TIMEOUT", "超时");
        assertTrue(service.finish(prepared, "execution", result, 80L, false));
        verify(quota, never()).consume(anyLong(), anyInt(), anyBoolean());
        verify(usage).record(eq(10L), eq(100L), eq("DIAGNOSIS"), eq(0), eq(0), eq(true), eq(80L),
                eq("FAIL"), contains("UPSTREAM_TIMEOUT"), isNull());
    }

    @Test
    void staleRunningAppearsAsRetryableFailure() {
        own((byte) 2, null, "int main(){}", null);
        SubmissionDiagnosis running = new SubmissionDiagnosis();
        running.setSubmissionId(1L);
        running.setUserId(10L);
        running.setStatus(SubmissionDiagnosis.RUNNING);
        running.setStartTime(LocalDateTime.now().minusMinutes(3));
        when(mapper.selectById(1L)).thenReturn(running);
        SubmissionDiagnosisView view = service.view(10L, 1L);
        assertEquals(SubmissionDiagnosis.FAILED, view.status());
        assertEquals("AI_DIAGNOSIS_INTERRUPTED", view.errorCode());
    }

    @Test
    void quotaRejectionDoesNotCreateOrCallModel() {
        own((byte) 2, null, "int main(){}", null);
        when(quota.tryAcquire(10L, false)).thenReturn(false);
        assertEquals(AiErrorCode.AI_QUOTA_EXCEEDED.key(), assertThrows(BusinessException.class,
                () -> service.claim(service.prepare(10L, 1L), false)).getErrorCode().key());
        verify(mapper, never()).insert(any(SubmissionDiagnosis.class));
        verifyNoInteractions(gateway);
    }
}
