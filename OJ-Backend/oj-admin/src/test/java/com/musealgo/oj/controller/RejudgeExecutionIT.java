package com.musealgo.oj.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.musealgo.oj.contest.service.ContestRankService;
import com.musealgo.oj.judge.application.RejudgeApplicationService;
import com.musealgo.oj.judge.dto.RejudgeBatchScope;
import com.musealgo.oj.judge.dto.RejudgeOperationVO;
import com.musealgo.oj.judge.dto.RejudgePreviewVO;
import com.musealgo.oj.judge.entity.JudgeTask;
import com.musealgo.oj.judge.entity.RejudgeItem;
import com.musealgo.oj.judge.entity.RejudgeOperation;
import com.musealgo.oj.judge.entity.Submission;
import com.musealgo.oj.judge.mapper.JudgeTaskMapper;
import com.musealgo.oj.judge.mapper.RejudgeItemMapper;
import com.musealgo.oj.judge.mapper.RejudgeOperationMapper;
import com.musealgo.oj.judge.mapper.SubmissionMapper;
import com.musealgo.oj.judge.service.JudgeTaskService;
import com.musealgo.oj.judge.worker.JudgeCompletionService;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T8 单提交重判真实 MySQL 验收（真实 Spring/MyBatis；判题 Worker 关闭，由测试模拟领取/完成/失败）。
 * <p>覆盖：A 冻结准入之外的正常重置、用例摘要、幂等、完成回写、计数重算、快照 stale、
 * 以及重试耗尽时恢复旧结果。已固化比赛/进行中比赛的准入由 RejudgeApplicationServiceTest 覆盖。</p>
 */
@SpringBootTest(properties = {
        "oj.judge.worker.enabled=false",
        "oj.judge.watchdog.enabled=false",
        "oj.messaging.rabbitmq-enabled=false"
})
class RejudgeExecutionIT {

    @Autowired private RejudgeApplicationService rejudgeService;
    @Autowired private SubmissionMapper submissions;
    @Autowired private JudgeTaskMapper judgeTasks;
    @Autowired private RejudgeItemMapper rejudgeItems;
    @Autowired private RejudgeOperationMapper rejudgeOperations;
    @Autowired private JudgeTaskService judgeTaskService;
    @Autowired private JudgeCompletionService completionService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ContestRankService contestRankService;

    private Long userId;
    private Long submissionId;
    private Long operationId;
    private final List<Long> extraSubmissionIds = new ArrayList<>();
    private Long vpContestId;
    private Long vpAttemptId;
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void fixture() {
        userId = insertUser();
        jdbc.update("INSERT INTO student_profile_snapshot(student_id, class_id, period_start, period_end, snapshot_json, stale) "
                        + "VALUES (?, NULL, ?, ?, '{}', 0)",
                userId, weekStart(today), weekStart(today).plusDays(6));
        submissionId = insertSubmission(1L);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM student_profile_snapshot WHERE student_id = ?", userId);
        jdbc.update("DELETE FROM outbox_event WHERE aggregate_id = ?", submissionId);
        jdbc.update("DELETE FROM submission_result WHERE submission_id = ?", submissionId);
        if (operationId != null) {
            jdbc.update("DELETE FROM rejudge_item WHERE operation_id = ?", operationId);
        }
        jdbc.update("DELETE FROM rejudge_item WHERE submission_id = ?", submissionId);
        jdbc.update("DELETE FROM rejudge_operation WHERE operator_id = ?", 990001L);
        jdbc.update("DELETE FROM judge_task WHERE submission_id = ?", submissionId);
        submissions.deleteById(submissionId);
        for (Long extra : extraSubmissionIds) {
            jdbc.update("DELETE FROM outbox_event WHERE aggregate_id = ?", extra);
            jdbc.update("DELETE FROM submission_result WHERE submission_id = ?", extra);
            jdbc.update("DELETE FROM judge_task WHERE submission_id = ?", extra);
            submissions.deleteById(extra);
        }
        extraSubmissionIds.clear();
        if (vpContestId != null) {
            jdbc.update("DELETE FROM contest_rank WHERE contest_id = ?", vpContestId);
            if (vpAttemptId != null) {
                jdbc.update("DELETE FROM vp_attempt WHERE id = ?", vpAttemptId);
            }
            jdbc.update("DELETE FROM contest WHERE id = ?", vpContestId);
        }
        jdbc.update("DELETE FROM user WHERE id = ?", userId);
    }

    @Test
    void rejudgeResetsJudgesCompletesAndRefreshesDerivedData() {
        JudgeTask task = insertCompletedTask(5, 3);
        RejudgeOperationVO created = rejudgeService.rejudgeSingleSubmission(
                submissionId, "T8 IT 重判", 990001L, "it-admin");
        operationId = created.id();
        assertEquals("RUNNING", created.status());
        assertEquals(1, created.totalItems());

        // 重置：提交回到待判、任务重试预算清零但历史 generation 保留
        Submission reset = submissions.selectById(submissionId);
        assertEquals((byte) 0, reset.getStatus());
        assertEquals("QUEUED", reset.getJudgePhase());
        JudgeTask resetTask = judgeTasks.selectById(task.getId());
        assertEquals(JudgeTask.STATUS_PENDING, resetTask.getStatus());
        assertEquals(0, resetTask.getAttemptCount());
        assertEquals(5, resetTask.getGeneration());
        RejudgeItem item = rejudgeItems.selectOpenBySubmissionId(submissionId);
        assertNotNull(item);
        assertEquals(64, item.getTestcaseDigest().length());
        assertNotNull(item.getOldResultJson());

        // 幂等：未完成前重复调用返回同一个 operation
        RejudgeOperationVO duplicated = rejudgeService.rejudgeSingleSubmission(
                submissionId, "重复请求", 990001L, "it-admin");
        assertEquals(created.id(), duplicated.id());

        // 模拟 Worker 领取 + 判题完成（WA）
        JudgeTask claimed = claimFor(submissionId);
        assertNotNull(claimed);
        Submission judged = new Submission();
        judged.setId(submissionId);
        judged.setStatus((byte) 2);
        judged.setScore(0);
        judged.setRunTime(5);
        judged.setRunMemory(100);
        judged.setJudgePhase("FINISHED");
        completionService.completeWithOutbox(claimed, judged, false);

        Submission after = submissions.selectById(submissionId);
        assertEquals((byte) 2, after.getStatus());
        assertEquals("FINISHED", after.getJudgePhase());
        RejudgeItem done = rejudgeItems.selectById(item.getId());
        assertEquals(RejudgeItem.STATUS_SUCCEEDED, done.getStatus());
        assertNotNull(done.getNewResultJson());
        RejudgeOperation operation = rejudgeOperations.selectById(operationId);
        assertEquals(RejudgeOperation.STATUS_COMPLETED, operation.getStatus());
        assertEquals(1, operation.getSucceededItems());

        // 计数按提交表重算（原 AC 变 WA → accepted_count 归零）
        Integer accepted = jdbc.queryForObject("SELECT accepted_count FROM user WHERE id = ?", Integer.class, userId);
        assertEquals(0, accepted);
        Integer submitCount = jdbc.queryForObject("SELECT submit_count FROM user WHERE id = ?", Integer.class, userId);
        assertEquals(1, submitCount);

        // 快照被标记过期（重判事件监听器；T8-2 将加对账）
        Integer stale = jdbc.queryForObject("SELECT stale FROM student_profile_snapshot WHERE student_id = ?", Integer.class, userId);
        assertEquals(1, stale);
    }

    @Test
    void retryExhaustionRestoresPreviousResultAndMarksOperationFailed() {
        JudgeTask task = insertCompletedTask(5, 3);
        RejudgeOperationVO created = rejudgeService.rejudgeSingleSubmission(
                submissionId, "T8 IT 失败恢复", 990001L, "it-admin");
        operationId = created.id();

        JudgeTask claimed = null;
        for (int i = 1; i <= 3; i++) {
            claimed = claimFor(submissionId);
            assertNotNull(claimed, "第 " + i + " 次应能领取到任务");
            judgeTaskService.fail(claimed, "judge0 down #" + i);
            if (i < 3) {
                // 模拟退避到期，便于下一次领取
                jdbc.update("UPDATE judge_task SET scheduled_at = DATE_SUB(NOW(), INTERVAL 1 SECOND) WHERE id = ?",
                        claimed.getId());
            }
        }

        Submission restored = submissions.selectById(submissionId);
        assertEquals((byte) 1, restored.getStatus());
        assertEquals(100, restored.getScore());
        assertEquals("FINISHED", restored.getJudgePhase());
        RejudgeItem item = rejudgeItems.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RejudgeItem>()
                .eq(RejudgeItem::getSubmissionId, submissionId)).get(0);
        assertEquals(RejudgeItem.STATUS_FAILED, item.getStatus());
        RejudgeOperation operation = rejudgeOperations.selectById(operationId);
        assertEquals(RejudgeOperation.STATUS_FAILED, operation.getStatus());
        assertEquals(1, operation.getFailedItems());
        JudgeTask taskAfter = judgeTasks.selectById(task.getId());
        assertEquals(JudgeTask.STATUS_FAILED, taskAfter.getStatus());
    }

    @Test
    void batchProblemRejudgeResetsAndCompletesEveryItem() {
        Long secondSubmission = insertSubmission(1L);
        extraSubmissionIds.add(secondSubmission);

        RejudgePreviewVO preview = rejudgeService.previewBatch(
                new RejudgeBatchScope("PROBLEM", 1L, null, null, null));
        assertTrue(preview.eligible() >= 2, preview.toString());
        assertFalse(preview.overLimit(), preview.toString());

        RejudgeOperationVO created = rejudgeService.rejudgeBatch(
                new RejudgeBatchScope("PROBLEM", 1L, null, null, null), "T8 IT 批量重判", 990002L, "it-admin");
        operationId = created.id();
        assertEquals(2, created.totalItems());
        assertEquals(JudgeTask.STATUS_PENDING, judgeTasks.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<JudgeTask>()
                        .eq(JudgeTask::getSubmissionId, submissionId)).getStatus());

        // 模拟 Worker 分批领取并完成（WA），直到两条都结束
        for (int round = 0; round < 5; round++) {
            List<JudgeTask> claimed = judgeTaskService.claimBatch(20).stream()
                    .filter(t -> t.getSubmissionId().equals(submissionId)
                            || t.getSubmissionId().equals(secondSubmission))
                    .toList();
            for (JudgeTask task : claimed) {
                Submission judged = new Submission();
                judged.setId(task.getSubmissionId());
                judged.setStatus((byte) 2);
                judged.setScore(0);
                judged.setRunTime(5);
                judged.setRunMemory(100);
                judged.setJudgePhase("FINISHED");
                completionService.completeWithOutbox(task, judged, false);
            }
            RejudgeOperation operation = rejudgeOperations.selectById(operationId);
            if (RejudgeOperation.STATUS_COMPLETED.equals(operation.getStatus())) {
                break;
            }
        }

        RejudgeOperation operation = rejudgeOperations.selectById(operationId);
        assertEquals(RejudgeOperation.STATUS_COMPLETED, operation.getStatus());
        assertEquals(2, operation.getSucceededItems());
        assertEquals((byte) 2, submissions.selectById(submissionId).getStatus());
        assertEquals((byte) 2, submissions.selectById(secondSubmission).getStatus());
        assertEquals(2, rejudgeItems.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RejudgeItem>()
                        .eq(RejudgeItem::getOperationId, operationId)).size());
    }

    @Test
    void finishedVpSubmissionCanBeRejudgedAndRecalculatesVpScore() {
        vpContestId = insertFinishedContest();
        vpAttemptId = insertFinishedVpAttempt(vpContestId);
        Long vpSubmission = insertVpSubmission(vpContestId, vpAttemptId);
        extraSubmissionIds.add(vpSubmission);

        RejudgeOperationVO created = rejudgeService.rejudgeSingleSubmission(
                vpSubmission, "T8-2b VP 重判", 990003L, "it-admin");
        operationId = created.id();
        assertEquals("RUNNING", created.status());
        assertEquals(1, created.totalItems());

        JudgeTask claimed = claimFor(vpSubmission);
        assertNotNull(claimed, "VP 重判任务应可领取");
        Submission judged = new Submission();
        judged.setId(vpSubmission);
        judged.setStatus((byte) 2);
        judged.setScore(0);
        judged.setRunTime(5);
        judged.setRunMemory(100);
        judged.setJudgePhase("FINISHED");
        completionService.completeWithOutbox(claimed, judged, false);

        // contest 监听器在 AFTER_COMMIT 重算 vp_attempt：原 AC 改为 WA 后 solved/score 应为 0
        var vp = jdbc.queryForMap("SELECT status, score, solved_count FROM vp_attempt WHERE id = ?", vpAttemptId);
        assertEquals("FINISHED", vp.get("status"));
        assertEquals(0, ((Number) vp.get("score")).intValue());
        assertEquals(0, ((Number) vp.get("solved_count")).intValue());
        RejudgeItem item = rejudgeItems.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RejudgeItem>()
                        .eq(RejudgeItem::getSubmissionId, vpSubmission)).get(0);
        assertEquals(RejudgeItem.STATUS_SUCCEEDED, item.getStatus());

        // T10-4：重判后 VP 成绩重算必须反映在全局榜（不写正式榜、不计 Rating）。
        var vpRow = contestRankService.getGlobalRankList(vpContestId).stream()
                .filter(r -> "VP".equals(r.getParticipantType()) && userId.equals(r.getUserId()))
                .findFirst().orElse(null);
        assertNotNull(vpRow, "全局榜应包含 VP 行");
        assertEquals(0, vpRow.getSolvedCount(), "重判为 WA 后 VP 过题数应归零");
        assertNull(vpRow.getRatingAfter(), "VP 不计 Rating");
    }

    // ------------------------------------------------------------------
    // fixture
    // ------------------------------------------------------------------

    private Long insertUser() {
        String username = "rejudge_it_" + UUID.randomUUID();
        jdbc.update("INSERT INTO user(username, password, nickname, rating) VALUES (?, 'test-only', ?, 1200)",
                username, username);
        return jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }

    private Long insertSubmission(Long problemId) {
        // 固定到“今天 10:00”：fixture 快照按同一周插入，避免周一/跨周边界时 listener 算出的周与 fixture 不一致。
        jdbc.update("INSERT INTO submission(problem_id, user_id, language, status, score, run_time, run_memory, "
                        + "create_time, judge_phase) VALUES (?, ?, 'cpp', 1, 100, 5, 100, ?, 'FINISHED')",
                problemId, userId, today.atTime(10, 0));
        return jdbc.queryForObject("SELECT id FROM submission WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, userId);
    }

    private JudgeTask insertCompletedTask(int generation, int attempt) {
        JudgeTask task = new JudgeTask();
        task.setSubmissionId(submissionId);
        task.setStatus(JudgeTask.STATUS_COMPLETED);
        task.setAttemptCount(attempt);
        task.setGeneration(generation);
        task.setScheduledAt(LocalDateTime.now().minusMinutes(5));
        task.setCompletedAt(LocalDateTime.now().minusMinutes(5));
        task.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        task.setUpdatedAt(LocalDateTime.now().minusMinutes(5));
        judgeTasks.insert(task);
        return task;
    }

    private JudgeTask claimFor(Long targetSubmissionId) {
        List<JudgeTask> claimed = judgeTaskService.claimBatch(20);
        return claimed.stream()
                .filter(t -> targetSubmissionId.equals(t.getSubmissionId()))
                .findFirst().orElse(null);
    }

    private Long insertFinishedContest() {
        String title = "t8-vp-it-" + UUID.randomUUID();
        LocalDateTime end = LocalDateTime.now().minusHours(1);
        jdbc.update("INSERT INTO contest(title, start_time, end_time, duration, type, mode, author_id, "
                        + "author_name, visible, is_deleted, rank_finalized_at) "
                        + "VALUES (?, ?, ?, 60, 'Public', 'ACM', 990001, 'it', 1, 0, NOW())",
                title, end.minusHours(1), end);
        return jdbc.queryForObject("SELECT id FROM contest WHERE title = ?", Long.class, title);
    }

    private Long insertFinishedVpAttempt(Long contestId) {
        LocalDateTime start = LocalDateTime.now().minusHours(2);
        jdbc.update("INSERT INTO vp_attempt(contest_id, user_id, status, start_time, end_time, submit_time, "
                        + "score, solved_count, penalty_time, rank_num, total_participants) "
                        + "VALUES (?, ?, 'FINISHED', ?, ?, ?, 100, 1, 0, 1, 1)",
                contestId, userId, start, start.plusHours(1), LocalDateTime.now().minusHours(1));
        return jdbc.queryForObject("SELECT id FROM vp_attempt WHERE contest_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, contestId);
    }

    private Long insertVpSubmission(Long contestId, Long attemptId) {
        jdbc.update("INSERT INTO submission(problem_id, user_id, language, status, score, run_time, run_memory, "
                        + "create_time, judge_phase, contest_id, vp_attempt_id) "
                        + "VALUES (1, ?, 'cpp', 1, 100, 5, 100, ?, 'FINISHED', ?, ?)",
                userId, today.atTime(10, 0), contestId, attemptId);
        return jdbc.queryForObject("SELECT id FROM submission WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, userId);
    }

    private static LocalDate weekStart(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
