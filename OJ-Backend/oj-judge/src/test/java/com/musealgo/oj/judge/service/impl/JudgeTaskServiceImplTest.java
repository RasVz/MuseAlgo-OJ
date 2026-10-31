package com.musealgo.oj.judge.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.musealgo.oj.judge.entity.JudgeTask;
import com.musealgo.oj.judge.entity.RejudgeItem;
import com.musealgo.oj.judge.entity.RejudgeOperation;
import com.musealgo.oj.judge.entity.Submission;
import com.musealgo.oj.judge.mapper.JudgeTaskMapper;
import com.musealgo.oj.judge.mapper.RejudgeItemMapper;
import com.musealgo.oj.judge.mapper.RejudgeOperationMapper;
import com.musealgo.oj.judge.mapper.SubmissionMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 判题任务服务单测（T8 generation 拆分后）：
 * 领取只递增 generation、不消耗重试预算；完成/失败 CAS 使用 generation；
 * 重试耗尽且存在重判条目时恢复旧结果。
 */
@ExtendWith(MockitoExtension.class)
class JudgeTaskServiceImplTest {

    @Mock
    private JudgeTaskMapper judgeTaskMapper;
    @Mock
    private SubmissionMapper submissionMapper;
    @Mock
    private RejudgeItemMapper rejudgeItemMapper;
    @Mock
    private RejudgeOperationMapper rejudgeOperationMapper;
    @InjectMocks
    private JudgeTaskServiceImpl service;

    private JudgeTask task(Long id, Long submissionId, int attempt, int generation) {
        JudgeTask t = new JudgeTask();
        t.setId(id);
        t.setSubmissionId(submissionId);
        t.setStatus(JudgeTask.STATUS_PENDING);
        t.setAttemptCount(attempt);
        t.setGeneration(generation);
        return t;
    }

    private void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "test"), JudgeTask.class);
    }

    @Test
    void claimBatchIncrementsGenerationAndKeepsRetryBudget() {
        when(judgeTaskMapper.claimTasks(2)).thenReturn(List.of(task(1L, 10L, 0, 0), task(2L, 11L, 2, 1)));

        List<JudgeTask> claimed = service.claimBatch(2);

        assertEquals(2, claimed.size());
        for (JudgeTask t : claimed) {
            assertEquals(JudgeTask.STATUS_PROCESSING, t.getStatus());
        }
        assertEquals(1, claimed.get(0).getGeneration());
        assertEquals(0, claimed.get(0).getAttemptCount());
        assertEquals(2, claimed.get(1).getGeneration());
        assertEquals(2, claimed.get(1).getAttemptCount());
        verify(judgeTaskMapper, times(2)).updateById(any(JudgeTask.class));
    }

    @Test
    void claimBatchReturnsEmptyWhenNoTasks() {
        when(judgeTaskMapper.claimTasks(anyInt())).thenReturn(List.of());

        assertTrue(service.claimBatch(8).isEmpty());
        verify(judgeTaskMapper, never()).updateById(any(JudgeTask.class));
    }

    @Test
    void claimBatchRejectsNonPositiveLimit() {
        assertTrue(service.claimBatch(0).isEmpty());
        assertTrue(service.claimBatch(-1).isEmpty());
        verify(judgeTaskMapper, never()).claimTasks(anyInt());
        verify(judgeTaskMapper, never()).updateById(any(JudgeTask.class));
    }

    @Test
    void completionUsesGenerationInsteadOfAttemptCount() {
        JudgeTask old = task(1L, 10L, 2, 1);
        when(judgeTaskMapper.completeIfProcessing(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(1), any(), any())).thenReturn(0);
        assertFalse(service.complete(old));
    }

    @Test
    void staleFailureCannotOverwriteReclaimedTaskOrSubmission() {
        initTableInfo();
        JudgeTask old = task(1L, 10L, 0, 1);
        service.fail(old, "late failure");
        verify(rejudgeItemMapper, never()).selectOpenBySubmissionId(any());
        verify(submissionMapper, never()).updateJudgePhase(any(), any());
        verify(submissionMapper, never()).updateById(any(Submission.class));
    }

    @Test
    void exhaustedFailureRestoresPreviousResultForRejudgeItem() {
        initTableInfo();
        JudgeTask task = task(1L, 10L, 2, 3);
        when(judgeTaskMapper.update(isNull(), any())).thenReturn(1);

        RejudgeItem item = new RejudgeItem();
        item.setId(5L);
        item.setOperationId(9L);
        item.setSubmissionId(10L);
        item.setStatus(RejudgeItem.STATUS_PENDING);
        item.setOldResultJson("{\"status\":2,\"score\":0,\"runTime\":10,\"runMemory\":100,"
                + "\"errorMessage\":null,\"judgePhase\":\"FINISHED\"}");
        when(rejudgeItemMapper.selectOpenBySubmissionId(10L)).thenReturn(item);
        Submission submission = new Submission();
        submission.setId(10L);
        submission.setStatus((byte) 0);
        submission.setScore(0);
        submission.setJudgePhase("QUEUED");
        when(submissionMapper.selectById(10L)).thenReturn(submission);
        RejudgeOperation operation = new RejudgeOperation();
        operation.setId(9L);
        operation.setTotalItems(1);
        operation.setSucceededItems(0);
        operation.setFailedItems(0);
        when(rejudgeOperationMapper.selectById(9L)).thenReturn(operation);

        service.fail(task, "judge0 unavailable on all retries");

        assertEquals((byte) 2, submission.getStatus());
        assertEquals("FINISHED", submission.getJudgePhase());
        verify(submissionMapper).updateById(submission);
        assertEquals(RejudgeItem.STATUS_FAILED, item.getStatus());
        verify(rejudgeItemMapper).updateById(item);
        assertEquals(RejudgeOperation.STATUS_FAILED, operation.getStatus());
        verify(rejudgeOperationMapper).updateById(operation);
        verify(submissionMapper, never()).updateJudgePhase(any(), any());
    }
}
