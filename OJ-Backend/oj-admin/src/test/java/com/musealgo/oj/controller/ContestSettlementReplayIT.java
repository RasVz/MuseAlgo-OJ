package com.musealgo.oj.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.musealgo.oj.contest.entity.vo.SettlementReplayPreviewVO;
import com.musealgo.oj.contest.entity.vo.SettlementReplayResultVO;
import com.musealgo.oj.contest.service.ContestRankService;
import com.musealgo.oj.contest.service.ContestSettlementReplayService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T8-3 C 真实 MySQL 验收：从最早受影响比赛起链式重放后续已固化比赛。
 * 覆盖：预览/确认串校验、结算轮次递增、user_rating/contest_rank 重写、
 * 相同数据重放后 user.rating 不变、审计记录与备份存在。
 */
@SpringBootTest(properties = {"oj.messaging.rabbitmq-enabled=false", "oj.judge.worker.enabled=false", "contest.replay.enabled=true"})
class ContestSettlementReplayIT {

    @Autowired private ContestRankService rankService;
    @Autowired private ContestSettlementReplayService replayService;
    @Autowired private JdbcTemplate jdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.musealgo.oj.api.user.UserRatingCommand ratingCommand;

    private Long userA;
    private Long userB;
    private Long contestA;
    private Long contestB;
    private final List<Long> temporarilyFinalized = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long id : new Long[]{contestA, contestB}) {
            if (id != null) {
                jdbc.update("DELETE FROM contest_settlement_replay WHERE from_contest_id = ?", id);
                jdbc.update("DELETE FROM user_rating WHERE contest_id = ?", id);
                jdbc.update("DELETE FROM contest_rank WHERE contest_id = ?", id);
                jdbc.update("DELETE FROM contest_settlement WHERE contest_id = ?", id);
                jdbc.update("DELETE FROM submission WHERE contest_id = ?", id);
                jdbc.update("DELETE FROM contest_user WHERE contest_id = ?", id);
                jdbc.update("DELETE FROM contest WHERE id = ?", id);
            }
        }
        for (Long id : new Long[]{userA, userB}) {
            if (id != null) {
                jdbc.update("DELETE FROM user_rating WHERE user_id = ?", id);
                jdbc.update("DELETE FROM user WHERE id = ?", id);
            }
        }
        for (Long id : temporarilyFinalized) {
            jdbc.update("UPDATE contest SET rank_finalized_at = NULL WHERE id = ?", id);
        }
        temporarilyFinalized.clear();
    }

    @Test
    void replayChainRewritesRanksAndRatingsIdempotently() {
        userA = insertUser(1500);
        userB = insertUser(1600);
        // ID 顺序与结束时间相反，不能依赖索引扫描顺序选 Rating 起点。
        contestB = insertContest("T8-3C-B", LocalDateTime.now().minusHours(1));
        contestA = insertContest("T8-3C-A", LocalDateTime.now().minusHours(2));
        insertSubmission(contestA, userA, 1);
        insertSubmission(contestA, userB, 3);
        insertSubmission(contestB, userA, 2);
        insertSubmission(contestB, userB, 1);

        markEarlierUnsettled(contestA, LocalDateTime.now().minusHours(2));
        rankService.finalizeContest(contestA);
        markEarlierUnsettled(contestB, LocalDateTime.now().minusHours(1));
        rankService.finalizeContest(contestB);

        Map<Long, Integer> ratingsBeforeReplay = Map.of(
                userA, ratingOf(userA),
                userB, ratingOf(userB));
        int ratingRowsBefore = count("SELECT COUNT(*) FROM user_rating WHERE contest_id IN (?, ?)", contestA, contestB);
        assertEquals(4, ratingRowsBefore, "两场都必须在赛程内正式参赛");
        var ranksBefore = jdbc.queryForList("SELECT * FROM contest_rank WHERE contest_id IN (?, ?) ORDER BY id", contestA, contestB);
        var historyBefore = jdbc.queryForList("SELECT * FROM user_rating WHERE contest_id IN (?, ?) ORDER BY id", contestA, contestB);
        var settlementsBefore = jdbc.queryForList("SELECT * FROM contest_settlement WHERE contest_id IN (?, ?) ORDER BY contest_id", contestA, contestB);
        int rankRowsBefore = count("SELECT COUNT(*) FROM contest_rank WHERE contest_id IN (?, ?)", contestA, contestB);

        SettlementReplayPreviewVO preview = replayService.preview(contestA);
        assertTrue(preview.canExecute(), String.join("；", preview.warnings()));
        assertEquals(2, preview.contests().size());
        assertEquals(2, preview.affectedUsers());

        SettlementReplayResultVO result = replayService.execute(
                contestA, "T8-3C IT 重放验证", 1L, preview.confirmation());

        assertEquals(2, result.contestCount());
        assertEquals(ratingsBeforeReplay.get(userA), ratingOf(userA));
        assertEquals(ratingsBeforeReplay.get(userB), ratingOf(userB));
        assertEquals(ratingRowsBefore, count("SELECT COUNT(*) FROM user_rating WHERE contest_id IN (?, ?)", contestA, contestB));
        assertEquals(rankRowsBefore, count("SELECT COUNT(*) FROM contest_rank WHERE contest_id IN (?, ?)", contestA, contestB));
        assertEquals(2, count("SELECT COUNT(*) FROM contest_settlement WHERE contest_id IN (?, ?) AND settlement_no = 2", contestA, contestB));
        var replay = jdbc.queryForMap("SELECT status, backup_json FROM contest_settlement_replay WHERE id = ?", result.replayId());
        assertEquals("COMPLETED", replay.get("status"));
        assertNotNull(replay.get("backup_json"));
        assertThrows(IllegalStateException.class, () -> replayService.execute(contestA, "旧预览不得二次执行", 1L, preview.confirmation()));
        var restore = replayService.previewRestore(result.replayId());
        assertTrue(restore.canExecute(), restore.warnings().toString());
        replayService.restore(result.replayId(), "验证完整反向恢复", 1L, restore.confirmation());
        assertEquals(ranksBefore, jdbc.queryForList("SELECT * FROM contest_rank WHERE contest_id IN (?, ?) ORDER BY id", contestA, contestB));
        assertEquals(historyBefore, jdbc.queryForList("SELECT * FROM user_rating WHERE contest_id IN (?, ?) ORDER BY id", contestA, contestB));
        assertEquals(settlementsBefore, jdbc.queryForList("SELECT * FROM contest_settlement WHERE contest_id IN (?, ?) ORDER BY contest_id", contestA, contestB));
        assertEquals(ratingsBeforeReplay.get(userA), ratingOf(userA));
        assertEquals("RESTORED", replayService.getReplay(result.replayId()).getStatus());
        assertThrows(IllegalStateException.class, () -> replayService.previewRestore(result.replayId()));
    }

    @Test
    void deletedSettledContestStaysInReplayChain() {
        userA = insertUser(1400); userB = insertUser(1700);
        contestA = insertContest("T8-deleted-A", LocalDateTime.now().minusHours(2));
        contestB = insertContest("T8-deleted-B", LocalDateTime.now().minusHours(1));
        insertSubmission(contestA, userA, 1); insertSubmission(contestA, userB, 3);
        insertSubmission(contestB, userA, 3); insertSubmission(contestB, userB, 1);
        markEarlierUnsettled(contestA, LocalDateTime.now().minusHours(2)); rankService.finalizeContest(contestA);
        rankService.finalizeContest(contestB);
        int before = ratingOf(userA);
        jdbc.update("UPDATE contest SET is_deleted=1 WHERE id=?", contestB);
        jdbc.update("UPDATE contest_rank SET award='保留奖项', is_visible=0 WHERE contest_id=? AND user_id=?", contestA, userA);
        var preview = replayService.preview(contestA);
        assertEquals(2, preview.contests().size());
        replayService.execute(contestA, "删除赛事链回归", 1L, preview.confirmation());
        assertEquals(before, ratingOf(userA));
        assertEquals("保留奖项", jdbc.queryForObject("SELECT award FROM contest_rank WHERE contest_id=? AND user_id=?", String.class, contestA, userA));
        assertEquals(0, jdbc.queryForObject("SELECT is_visible FROM contest_rank WHERE contest_id=? AND user_id=?", Integer.class, contestA, userA));
    }

    @Test
    void entertainmentNeedsNoRatingSnapshotAndChangedSourceBlocksRestore() {
        userA = insertUser(1400);
        contestA = insertContest("T8-entertainment", LocalDateTime.now().minusHours(1));
        jdbc.update("UPDATE contest SET category='ENTERTAINMENT' WHERE id=?", contestA);
        insertSubmission(contestA, userA, 1);
        markEarlierUnsettled(contestA, LocalDateTime.now().minusHours(1)); rankService.finalizeContest(contestA);
        var preview = replayService.preview(contestA);
        assertTrue(preview.canExecute(), preview.warnings().toString());
        var result = replayService.execute(contestA, "娱乐赛不需要 Rating 基准", 1L, preview.confirmation());
        assertEquals(1400, ratingOf(userA));
        assertEquals(0, count("SELECT COUNT(*) FROM user_rating WHERE contest_id=?", contestA));
        jdbc.update("UPDATE contest_rank SET award='后续颁奖' WHERE contest_id=?", contestA);
        var restore = replayService.previewRestore(result.replayId());
        assertFalse(restore.canExecute());
        assertThrows(IllegalStateException.class, () -> replayService.restore(result.replayId(), "不得覆盖后续颁奖", 1L, restore.confirmation()));
    }

    @Test
    void firstMissingSnapshotAndUnexpectedParticipantFailClosed() {
        userA = insertUser(1400); userB = insertUser(1700);
        contestA = insertContest("T8-snapshot-A", LocalDateTime.now().minusHours(2));
        contestB = insertContest("T8-snapshot-B", LocalDateTime.now().minusHours(1));
        insertSubmission(contestA, userA, 1); insertSubmission(contestB, userA, 1);
        markEarlierUnsettled(contestA, LocalDateTime.now().minusHours(2)); rankService.finalizeContest(contestA);
        rankService.finalizeContest(contestB);
        jdbc.update("UPDATE contest_rank SET rating_before=NULL WHERE contest_id=?", contestA);
        assertFalse(replayService.preview(contestA).canExecute(), "后续快照不能代替首次正式参赛快照");
        insertSubmission(contestB, userB, 2); // 模拟历史不一致；当前 HTTP 重判入口仍冻结已结算赛事。
        assertTrue(replayService.preview(contestB).warnings().stream().anyMatch(w -> w.contains("参与者")));
    }

    @Test
    void failedExecutionRollsBackAllWritesAndBackupRecord() {
        userA = insertUser(1400); userB = insertUser(1700);
        contestA = insertContest("T8-rollback", LocalDateTime.now().minusHours(1));
        insertSubmission(contestA, userA, 1); insertSubmission(contestA, userB, 3);
        markEarlierUnsettled(contestA, LocalDateTime.now().minusHours(1)); rankService.finalizeContest(contestA);
        var ranks = jdbc.queryForList("SELECT * FROM contest_rank WHERE contest_id=? ORDER BY id", contestA);
        var history = jdbc.queryForList("SELECT * FROM user_rating WHERE contest_id=? ORDER BY id", contestA);
        int rating = ratingOf(userA);
        var preview = replayService.preview(contestA);
        org.mockito.Mockito.doThrow(new IllegalStateException("injected rating update failure"))
                .when(ratingCommand).batchUpdateRatings(org.mockito.ArgumentMatchers.anyMap());
        assertThrows(IllegalStateException.class, () -> replayService.execute(contestA, "失败回滚演练", 1L, preview.confirmation()));
        assertEquals(ranks, jdbc.queryForList("SELECT * FROM contest_rank WHERE contest_id=? ORDER BY id", contestA));
        assertEquals(history, jdbc.queryForList("SELECT * FROM user_rating WHERE contest_id=? ORDER BY id", contestA));
        assertEquals(rating, ratingOf(userA));
        assertEquals(0, count("SELECT COUNT(*) FROM contest_settlement_replay WHERE from_contest_id=?", contestA));
    }

    private int ratingOf(Long userId) {
        return jdbc.queryForObject("SELECT rating FROM user WHERE id = ?", Integer.class, userId);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private void markEarlierUnsettled(Long targetContestId, LocalDateTime endTime) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM contest WHERE id <> ? AND rank_finalized_at IS NULL AND end_time < ?",
                Long.class, targetContestId, endTime);
        for (Long id : ids) {
            jdbc.update("UPDATE contest SET rank_finalized_at = NOW() WHERE id = ?", id);
            temporarilyFinalized.add(id);
        }
    }

    private Long insertUser(int rating) {
        String username = "t83c_" + UUID.randomUUID();
        jdbc.update("INSERT INTO user(username, password, nickname, rating) VALUES (?, 'test-only', ?, ?)",
                username, username, rating);
        return jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }

    private Long insertContest(String titlePrefix, LocalDateTime end) {
        String title = titlePrefix + "-" + UUID.randomUUID();
        jdbc.update("INSERT INTO contest(title, start_time, end_time, duration, type, mode, category, "
                        + "author_id, author_name, visible, is_deleted) "
                        + "VALUES (?, ?, ?, 60, 'Public', 'ACM', 'FORMAL', 1, 'it', 1, 0)",
                title, end.minusHours(1), end);
        return jdbc.queryForObject("SELECT id FROM contest WHERE title = ?", Long.class, title);
    }

    private void insertSubmission(Long contestId, Long userId, int runTime) {
        jdbc.update("INSERT INTO submission(problem_id, user_id, language, status, score, run_time, run_memory, "
                        + "create_time, judge_phase, contest_id) VALUES (1, ?, 'cpp', 1, 100, ?, 100, ?, 'FINISHED', ?)",
                userId, runTime, jdbc.queryForObject("SELECT start_time FROM contest WHERE id=?", LocalDateTime.class, contestId).plusMinutes(runTime), contestId);
    }
}
