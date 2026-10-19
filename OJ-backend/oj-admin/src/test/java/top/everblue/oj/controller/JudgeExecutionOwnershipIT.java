package top.everblue.oj.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import top.everblue.oj.judge.entity.*;
import top.everblue.oj.judge.mapper.*;
import top.everblue.oj.judge.service.JudgeTaskService;
import top.everblue.oj.judge.worker.*;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

/** 真实MySQL：任务代次条件更新、迟到结果隔离和完成事务回滚。仅在隔离测试库运行。 */
@SpringBootTest(properties = {"oj.judge.watchdog.enabled=false", "oj.messaging.rabbitmq-enabled=false"})
class JudgeExecutionOwnershipIT {
    @Autowired SubmissionMapper submissions;
    @Autowired JudgeTaskMapper taskMapper;
    @Autowired JudgeTaskService tasks;
    @Autowired JudgeCompletionService completion;
    @Autowired JdbcTemplate jdbc;
    Submission submission;
    JudgeTask current;
    @BeforeEach void fixture() {
        submission = new Submission(); submission.setProblemId(1L); submission.setUserId(1L); submission.setLanguage("cpp");
        submission.setStatus((byte)0); submission.setScore(0); submission.setJudgePhase("QUEUED");
        submissions.insert(submission);
        current = new JudgeTask(); current.setSubmissionId(submission.getId()); current.setStatus("PROCESSING");
        current.setAttemptCount(2); current.setLockedAt(LocalDateTime.now()); current.setScheduledAt(LocalDateTime.now());
        current.setCreatedAt(LocalDateTime.now()); current.setUpdatedAt(LocalDateTime.now()); taskMapper.insert(current);
    }
    JudgeTask old() { var old = new JudgeTask(); old.setId(current.getId()); old.setSubmissionId(submission.getId()); old.setAttemptCount(1); return old; }
    Submission result(int score) { var s = new Submission(); s.setId(submission.getId()); s.setStatus((byte)1); s.setScore(score); s.setRunTime(5); s.setRunMemory(100); s.setJudgePhase("FINISHED"); return s; }
    long events() { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?", Long.class, submission.getId()); }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM outbox_event WHERE aggregate_id=?", submission.getId());
        jdbc.update("DELETE FROM submission_result WHERE submission_id=?", submission.getId());
        taskMapper.deleteById(current.getId()); submissions.deleteById(submission.getId());
    }
    @Test void lateExecutionCannotWritePhaseCompleteOrFailNewAttempt() {
        assertEquals(0, submissions.updateOwnedPhase(submission.getId(), current.getId(), 1, "FINISHED"));
        assertThrows(StaleJudgeExecutionException.class, () -> completion.completeWithOutbox(old(), result(99), true));
        tasks.fail(old(), "late failure");
        assertEquals("PROCESSING", taskMapper.selectById(current.getId()).getStatus());
        assertEquals("QUEUED", submissions.selectById(submission.getId()).getJudgePhase()); assertEquals(0, events());
        assertEquals(1, submissions.updateOwnedPhase(submission.getId(), current.getId(), 2, "JUDGING"));
        completion.completeWithOutbox(current, result(100), true);
        assertEquals("COMPLETED", taskMapper.selectById(current.getId()).getStatus());
        assertEquals(100, submissions.selectById(submission.getId()).getScore()); assertEquals(1, events());
        assertThrows(StaleJudgeExecutionException.class, () -> completion.completeWithOutbox(old(), result(0), true));
        assertEquals(100, submissions.selectById(submission.getId()).getScore()); assertEquals(1, events());
    }
    @Test void normalizedResultFailureRollsBackCompletionAndMainResult() {
        // submission.score是有符号数，规范化结果为unsigned；后一步失败应回滚前面的两次写入。
        assertThrows(org.springframework.dao.DataAccessException.class, () -> completion.completeWithOutbox(current, result(-1), true));
        assertEquals("PROCESSING", taskMapper.selectById(current.getId()).getStatus());
        assertEquals(0, submissions.selectById(submission.getId()).getScore()); assertEquals(0, events());
    }
    @Test void retryClearsLockAndStaleFailureDoesNotConsumeAnotherAttempt() {
        tasks.fail(current, "temporary failure");
        JudgeTask retry = taskMapper.selectById(current.getId());
        assertEquals("PENDING", retry.getStatus()); assertNull(retry.getLockedAt()); assertEquals(2, retry.getAttemptCount());
        assertTrue(retry.getScheduledAt().isAfter(LocalDateTime.now()));
        tasks.fail(current, "late failure");
        assertEquals("temporary failure", taskMapper.selectById(current.getId()).getLastError());
    }
}
