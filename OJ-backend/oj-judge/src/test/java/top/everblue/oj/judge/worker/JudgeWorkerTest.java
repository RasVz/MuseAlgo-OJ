package top.everblue.oj.judge.worker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import top.everblue.oj.judge.entity.JudgeTask;
import top.everblue.oj.judge.metrics.JudgeMetrics;
import top.everblue.oj.judge.service.JudgeTaskService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 判题 Worker（高并发方案 A1/A2）单元测试：
 * 批量领取的任务被投递到判题线程池并行执行；单任务失败不影响同批其他任务；
 * 线程池满载时不再领取（背压留在任务表，不占调度线程）。
 */
@ExtendWith(MockitoExtension.class)
class JudgeWorkerTest {

    @Mock
    private JudgeTaskService judgeTaskService;
    @Mock
    private JudgeCompletionService judgeCompletionService;
    @Mock
    private JudgeOrchestrator judgeOrchestrator;
    @Mock
    private JudgeMetrics judgeMetrics;

    private ThreadPoolTaskExecutor judgeExecutor;

    private JudgeWorker worker;

    @BeforeEach
    void setUp() {
        // 模拟判题线程池：core=8、空闲（active=0）、execute 同步执行（便于断言）
        judgeExecutor = mock(ThreadPoolTaskExecutor.class);
        lenient().when(judgeExecutor.getCorePoolSize()).thenReturn(8);
        lenient().when(judgeExecutor.getActiveCount()).thenReturn(0);
        lenient().doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(judgeExecutor).execute(any(Runnable.class));

        worker = new JudgeWorker(judgeTaskService, judgeOrchestrator,
                judgeMetrics, judgeExecutor);
        ReflectionTestUtils.setField(worker, "batchSize", 8);
    }

    private JudgeTask task(Long id, Long submissionId) {
        JudgeTask t = new JudgeTask();
        t.setId(id);
        t.setSubmissionId(submissionId);
        t.setStatus(JudgeTask.STATUS_PROCESSING);
        t.setAttemptCount(1);
        return t;
    }

    @Test
    void pollAndJudgeDispatchesAllClaimedTasks() {
        when(judgeTaskService.claimBatch(8)).thenReturn(List.of(task(1L, 10L), task(2L, 11L)));

        worker.pollAndJudge();

        verify(judgeOrchestrator, times(1)).executeJudge(org.mockito.ArgumentMatchers.argThat(t -> t.getSubmissionId().equals(10L)));
        verify(judgeOrchestrator, times(1)).executeJudge(org.mockito.ArgumentMatchers.argThat(t -> t.getSubmissionId().equals(11L)));

    }

    @Test
    void pollAndJudgeSkipsWhenNoTasks() {
        when(judgeTaskService.claimBatch(8)).thenReturn(List.of());

        worker.pollAndJudge();

        verify(judgeOrchestrator, never()).executeJudge(any());

    }

    @Test
    void singleTaskFailureDoesNotAffectSiblings() {
        when(judgeTaskService.claimBatch(8)).thenReturn(List.of(task(1L, 10L), task(2L, 11L)));
        doThrow(new IllegalStateException("judge0 down")).when(judgeOrchestrator).executeJudge(org.mockito.ArgumentMatchers.argThat(t -> t.getSubmissionId().equals(10L)));

        worker.pollAndJudge();

        // 失败任务走 fail 流程；同批其他任务正常完成
        verify(judgeTaskService).fail(org.mockito.ArgumentMatchers.argThat(t -> t.getId().equals(1L) && t.getAttemptCount() == 1), anyString());
        verify(judgeOrchestrator, times(1)).executeJudge(org.mockito.ArgumentMatchers.argThat(t -> t.getSubmissionId().equals(11L)));

    }

    @Test
    void claimFailureIsLoggedAndSkipped() {
        when(judgeTaskService.claimBatch(8)).thenThrow(new RuntimeException("db error"));

        worker.pollAndJudge();

        verify(judgeOrchestrator, never()).executeJudge(any());
    }

    @Test
    void doesNotClaimWhenPoolIsSaturated() {
        // 判题线程池满载（active=8=core）：本轮不领取，背压留在任务表
        when(judgeExecutor.getActiveCount()).thenReturn(8);

        worker.pollAndJudge();

        verify(judgeTaskService, never()).claimBatch(anyInt());
        verify(judgeOrchestrator, never()).executeJudge(any());
    }
}
