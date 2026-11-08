package com.musealgo.oj.judge.worker;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import com.musealgo.oj.api.judge.RejudgePostProcessEvent;
import com.musealgo.oj.api.user.UserStatsCommand;
import com.musealgo.oj.judge.entity.*;
import com.musealgo.oj.judge.mapper.*;
import com.musealgo.oj.judge.service.JudgeTaskService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JudgeCompletionServiceTest {
    final JudgeTaskService tasks = mock(JudgeTaskService.class);
    final OutboxEventMapper events = mock(OutboxEventMapper.class);
    final SubmissionMapper submissions = mock(SubmissionMapper.class);
    final SubmissionResultMapper results = mock(SubmissionResultMapper.class);
    final RejudgeItemMapper rejudgeItems = mock(RejudgeItemMapper.class);
    final RejudgeOperationMapper rejudgeOperations = mock(RejudgeOperationMapper.class);
    final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    final UserStatsCommand userStats = mock(UserStatsCommand.class);
    final JudgeCompletionService service = new JudgeCompletionService(
            tasks, events, submissions, results, rejudgeItems, rejudgeOperations, publisher, userStats);

    JudgeTask task() {
        var t = new JudgeTask();
        t.setId(1L);
        t.setSubmissionId(10L);
        t.setAttemptCount(1);
        t.setGeneration(3);
        return t;
    }

    private Submission acceptedSubmission() {
        var s = new Submission();
        s.setId(10L);
        s.setUserId(7L);
        s.setProblemId(3L);
        s.setStatus((byte) 1);
        s.setScore(100);
        s.setCreateTime(java.time.LocalDateTime.now());
        return s;
    }

    @Test
    void staleExecutionCannotWriteResultOrPublishEvent() {
        assertThrows(StaleJudgeExecutionException.class,
                () -> service.completeWithOutbox(task(), new Submission(), true));
        verifyNoInteractions(submissions, results, events, rejudgeItems, publisher, userStats);
    }

    @Test
    void normalCompletionMaintainsAcceptedCountWithoutPublishingRejudgeEvent() {
        var t = task();
        var s = acceptedSubmission();
        when(tasks.complete(t)).thenReturn(true);
        when(events.insert(any(OutboxEvent.class))).thenReturn(1);
        when(submissions.updateById(s)).thenReturn(1);
        when(rejudgeItems.selectOpenBySubmissionId(10L)).thenReturn(null);
        when(submissions.selectCount(any())).thenReturn(0L);

        service.completeWithOutbox(t, s, false);

        verify(userStats).incrementAcceptedProblemCount(7L);
        verify(userStats, never()).recalculateCounters(any());
        verifyNoInteractions(publisher);
        verify(events).insert(any(OutboxEvent.class));
    }

    @Test
    void laterAcceptDoesNotIncrementAgain() {
        var t = task();
        var s = acceptedSubmission();
        when(tasks.complete(t)).thenReturn(true);
        when(events.insert(any(OutboxEvent.class))).thenReturn(1);
        when(submissions.updateById(s)).thenReturn(1);
        when(rejudgeItems.selectOpenBySubmissionId(10L)).thenReturn(null);
        when(submissions.selectCount(any())).thenReturn(1L);

        service.completeWithOutbox(t, s, false);

        verifyNoInteractions(userStats);
    }

    @Test
    void rejudgeCompletionWritesItemOperationRecalculatesAndPublishesEvent() {
        var t = task();
        var s = acceptedSubmission();
        when(tasks.complete(t)).thenReturn(true);
        when(events.insert(any(OutboxEvent.class))).thenReturn(1);
        when(submissions.updateById(s)).thenReturn(1);
        RejudgeItem item = new RejudgeItem();
        item.setId(5L);
        item.setOperationId(9L);
        item.setSubmissionId(10L);
        item.setStatus(RejudgeItem.STATUS_PENDING);
        when(rejudgeItems.selectOpenBySubmissionId(10L)).thenReturn(item);
        RejudgeOperation op = new RejudgeOperation();
        op.setId(9L);
        op.setTotalItems(1);
        op.setSucceededItems(0);
        op.setFailedItems(0);
        when(rejudgeOperations.selectForUpdate(9L)).thenReturn(op);
        when(rejudgeOperations.updateById(op)).thenReturn(1);
        when(rejudgeItems.updateById(any(RejudgeItem.class))).thenReturn(1);

        service.completeWithOutbox(t, s, false);

        assertEquals(RejudgeItem.STATUS_SUCCEEDED, item.getStatus());
        assertNotNull(item.getNewResultJson());
        verify(rejudgeItems).updateById(item);
        assertEquals(RejudgeOperation.STATUS_COMPLETED, op.getStatus());
        assertEquals(1, op.getSucceededItems());
        verify(rejudgeOperations).updateById(op);
        verify(userStats).recalculateCounters(7L);
        verify(userStats, never()).incrementAcceptedProblemCount(any());
        verify(publisher).publishEvent(any(RejudgePostProcessEvent.class));
    }
}
