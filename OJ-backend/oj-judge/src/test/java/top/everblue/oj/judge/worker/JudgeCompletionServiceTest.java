package top.everblue.oj.judge.worker;

import org.junit.jupiter.api.Test;
import top.everblue.oj.judge.entity.*;
import top.everblue.oj.judge.mapper.*;
import top.everblue.oj.judge.service.JudgeTaskService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JudgeCompletionServiceTest {
    final JudgeTaskService tasks = mock(JudgeTaskService.class);
    final OutboxEventMapper events = mock(OutboxEventMapper.class);
    final SubmissionMapper submissions = mock(SubmissionMapper.class);
    final SubmissionResultMapper results = mock(SubmissionResultMapper.class);
    final JudgeCompletionService service = new JudgeCompletionService(tasks, events, submissions, results);
    JudgeTask task() { var t = new JudgeTask(); t.setId(1L); t.setSubmissionId(10L); t.setAttemptCount(1); return t; }
    @Test void staleExecutionCannotWriteResultOrPublishEvent() {
        assertThrows(StaleJudgeExecutionException.class, () -> service.completeWithOutbox(task(), new Submission(), true));
        verifyNoInteractions(submissions, results, events);
    }
    @Test void ownedResultIsWrittenAfterConditionalCompletionAndBeforeEvent() {
        var t = task(); var s = new Submission(); s.setId(10L); s.setStatus((byte)1); s.setScore(100);
        when(tasks.complete(t)).thenReturn(true); when(submissions.updateById(s)).thenReturn(1);
        service.completeWithOutbox(t, s, true);
        var order = inOrder(tasks, submissions, results, events);
        order.verify(tasks).complete(t); order.verify(submissions).updateById(s);
        order.verify(results).upsert(any(SubmissionResult.class)); order.verify(events).insert(any(OutboxEvent.class));
    }
}
