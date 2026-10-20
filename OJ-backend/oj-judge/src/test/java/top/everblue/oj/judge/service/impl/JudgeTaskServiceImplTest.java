package top.everblue.oj.judge.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.everblue.oj.judge.entity.JudgeTask;
import top.everblue.oj.judge.mapper.JudgeTaskMapper;
import top.everblue.oj.judge.mapper.SubmissionMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批量领取（高并发方案 A2）单元测试：
 * 领取任务被标记 PROCESSING 且 attempt+1；空任务/非法参数返回空列表。
 */
@ExtendWith(MockitoExtension.class)
class JudgeTaskServiceImplTest {

    @Mock
    private JudgeTaskMapper judgeTaskMapper;
    @Mock
    private SubmissionMapper submissionMapper;
    @InjectMocks
    private JudgeTaskServiceImpl service;

    private JudgeTask task(Long id, Long submissionId, int attempt) {
        JudgeTask t = new JudgeTask();
        t.setId(id);
        t.setSubmissionId(submissionId);
        t.setStatus(JudgeTask.STATUS_PENDING);
        t.setAttemptCount(attempt);
        return t;
    }

    @Test
    void claimBatchMarksTasksProcessingAndIncrementsAttempt() {
        when(judgeTaskMapper.claimTasks(2)).thenReturn(List.of(task(1L, 10L, 0), task(2L, 11L, 1)));

        List<JudgeTask> claimed = service.claimBatch(2);

        assertEquals(2, claimed.size());
        for (JudgeTask t : claimed) {
            assertEquals(JudgeTask.STATUS_PROCESSING, t.getStatus());
        }
        assertEquals(1, claimed.get(0).getAttemptCount()); // 0 → 1
        assertEquals(2, claimed.get(1).getAttemptCount()); // 1 → 2
        verify(judgeTaskMapper, times(2)).updateById(any(JudgeTask.class));
    }

    @Test
    void claimBatchReturnsEmptyWhenNoTasks() {
        when(judgeTaskMapper.claimTasks(anyInt())).thenReturn(List.of());

        List<JudgeTask> claimed = service.claimBatch(8);

        assertTrue(claimed.isEmpty());
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
    void completionUsesClaimedAttemptInsteadOfCurrentStatusAlone() {
        JudgeTask old = task(1L, 10L, 1);
        when(judgeTaskMapper.completeIfProcessing(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(1), any(), any())).thenReturn(0);
        org.junit.jupiter.api.Assertions.assertFalse(service.complete(old));
    }

    @Test
    void staleFailureCannotOverwriteReclaimedTaskOrSubmission() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "test"), JudgeTask.class);
        JudgeTask old = task(1L, 10L, 3);
        service.fail(old, "late failure");
        var captor = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(judgeTaskMapper).update(org.mockito.ArgumentMatchers.isNull(), captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("attempt_count"));
        assertTrue(captor.getValue().getSqlSegment().contains("status"));
        org.mockito.Mockito.verifyNoInteractions(submissionMapper);
    }

}
