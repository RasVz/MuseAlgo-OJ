package com.musealgo.oj.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.musealgo.oj.contest.entity.ContestRank;
import com.musealgo.oj.contest.entity.vo.ContestRankVO;
import com.musealgo.oj.contest.service.ContestRankService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T10-2 真实 MySQL：正式榜只含正式选手；全局榜合并正式/打星号/VP 并统一连续编号；
 * 结算 Rating 只作用于正式选手。
 */
@SpringBootTest(properties = {"oj.messaging.rabbitmq-enabled=false", "oj.judge.worker.enabled=false",
        "oj.contest.settlement-initial-delay-ms=86400000"})
class ContestGlobalRankIT {

    @Autowired private ContestRankService rankService;
    @Autowired private JdbcTemplate jdbc;

    private Long contestId;
    private Long officialUser;
    private Long starredUser;
    private Long vpUser;

    @AfterEach
    void cleanup() {
        if (contestId != null) {
            jdbc.update("DELETE FROM user_rating WHERE contest_id = ?", contestId);
            jdbc.update("DELETE FROM contest_settlement WHERE contest_id = ?", contestId);
            jdbc.update("DELETE FROM contest_rank WHERE contest_id = ?", contestId);
            jdbc.update("DELETE FROM contest_user WHERE contest_id = ?", contestId);
            jdbc.update("DELETE FROM vp_attempt WHERE contest_id = ?", contestId);
            jdbc.update("DELETE FROM submission WHERE contest_id = ?", contestId);
            jdbc.update("DELETE FROM contest WHERE id = ?", contestId);
        }
        for (Long id : new Long[]{officialUser, starredUser, vpUser}) {
            if (id != null) jdbc.update("DELETE FROM user WHERE id = ?", id);
        }
    }

    @Test
    void globalBoardMergesStarredAndVpWithContinuousRanks() {
        officialUser = insertUser(1500);
        starredUser = insertUser(1900);
        vpUser = insertUser(1400);
        contestId = insertContest(true);
        insertRank(officialUser, 1, 2, 10, 0, "OFFICIAL");
        insertRank(starredUser, 2, 2, 5, 0, "STARRED");
        insertVpAttempt(vpUser, 1, 0);

        List<ContestRankVO> global = rankService.getGlobalRankList(contestId);
        assertEquals(3, global.size());
        // ACM：过题数相同，罚时更少者更强；全局榜统一连续编号
        assertEquals(starredUser, global.get(0).getUserId());
        assertEquals("STARRED", global.get(0).getParticipantType());
        assertEquals(1, global.get(0).getRankNum());
        assertEquals(officialUser, global.get(1).getUserId());
        assertEquals("OFFICIAL", global.get(1).getParticipantType());
        assertEquals(2, global.get(1).getRankNum());
        assertEquals(vpUser, global.get(2).getUserId());
        assertEquals("VP", global.get(2).getParticipantType());
        assertEquals(3, global.get(2).getRankNum());

        List<ContestRank> official = rankService.getFinalRankList(contestId);
        assertEquals(1, official.size());
        assertEquals(officialUser, official.get(0).getUserId());
        assertTrue(rankService.exportGlobalCsv(contestId).contains("STARRED"));
    }

    @Test
    void finalizeRatesOnlyOfficialParticipants() {
        officialUser = insertUser(1500);
        starredUser = insertUser(1900);
        contestId = insertContest(false);
        insertRegistration(officialUser, 1500, 1);
        insertRegistration(starredUser, 1900, 0);
        insertSubmission(officialUser, 2, 100);
        insertSubmission(starredUser, 2, 100);

        // 结算要求没有更早的未结算比赛；测试库中的种子比赛可能未固化，临时标记并在结束后恢复。
        var earlierUnsettled = jdbc.queryForList(
                "SELECT id FROM contest WHERE id <> ? AND rank_finalized_at IS NULL AND end_time < ?",
                Long.class, contestId, LocalDateTime.now().minusHours(1));
        jdbc.update("UPDATE contest SET rank_finalized_at = NOW() WHERE id <> ? AND rank_finalized_at IS NULL AND end_time < ?",
                contestId, LocalDateTime.now().minusHours(1));
        try {
            rankService.finalizeContest(contestId);
        } finally {
            for (Long id : earlierUnsettled) {
                jdbc.update("UPDATE contest SET rank_finalized_at = NULL WHERE id = ?", id);
            }
        }

        List<ContestRank> rows = jdbc.query("SELECT user_id, participant_type, rating_after FROM contest_rank "
                        + "WHERE contest_id = ? ORDER BY user_id", (rs, i) -> {
                    ContestRank rank = new ContestRank();
                    rank.setUserId(rs.getLong("user_id"));
                    rank.setParticipantType(rs.getString("participant_type"));
                    rank.setRatingAfter(rs.getObject("rating_after", Integer.class));
                    return rank;
                }, contestId);
        assertEquals(2, rows.size());
        Map<Long, ContestRank> byUser = rows.stream().collect(java.util.stream.Collectors.toMap(ContestRank::getUserId, r -> r));
        assertEquals("OFFICIAL", byUser.get(officialUser).getParticipantType());
        assertNotNull(byUser.get(officialUser).getRatingAfter());
        assertEquals("STARRED", byUser.get(starredUser).getParticipantType());
        assertNull(byUser.get(starredUser).getRatingAfter());
        assertEquals(1900, jdbc.queryForObject("SELECT rating FROM user WHERE id = ?", Integer.class, starredUser));
        assertEquals(1, rankService.getFinalRankList(contestId).size());
        // T8-3 D：结算版本落库，历史成绩带算法版本
        var settlement = jdbc.queryForMap("SELECT algorithm_version, rating_algorithm_version, settlement_no "
                + "FROM contest_settlement WHERE contest_id = ?", contestId);
        assertEquals("v1", settlement.get("algorithm_version"));
        assertEquals("elo-v1", settlement.get("rating_algorithm_version"));
        assertEquals(1, ((Number) settlement.get("settlement_no")).intValue());
        var versions = jdbc.queryForList("SELECT algorithm_version FROM contest_rank WHERE contest_id = ?",
                String.class, contestId);
        assertTrue(versions.stream().allMatch("v1"::equals));
    }

    @Test
    void entertainmentContestHasNoOfficialBoardButHasGlobalBoard() {
        officialUser = insertUser(1500);
        contestId = insertContest(true, "ENTERTAINMENT");
        insertRank(officialUser, 1, 2, 10, 0, "OFFICIAL");

        assertTrue(rankService.getFinalRankList(contestId).isEmpty());
        assertEquals(1, rankService.getGlobalRankList(contestId).size());
        assertEquals("OFFICIAL", rankService.getGlobalRankList(contestId).get(0).getParticipantType());
    }

    private Long insertUser(int rating) {
        String username = "t10_rank_" + UUID.randomUUID();
        jdbc.update("INSERT INTO user(username, password, nickname, rating) VALUES (?, 'test-only', ?, ?)",
                username, username, rating);
        return jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }

    private Long insertContest(boolean finalized) {
        return insertContest(finalized, "FORMAL");
    }

    private Long insertContest(boolean finalized, String category) {
        String title = "t10-rank-" + UUID.randomUUID();
        LocalDateTime end = LocalDateTime.now().minusHours(1);
        jdbc.update("INSERT INTO contest(title, start_time, end_time, duration, type, mode, category, "
                        + "author_id, author_name, visible, is_deleted, rank_finalized_at) "
                        + "VALUES (?, ?, ?, 60, 'Public', 'ACM', ?, 1, 'it', 1, 0, ?)",
                title, end.minusHours(1), end, category, finalized ? LocalDateTime.now() : null);
        return jdbc.queryForObject("SELECT id FROM contest WHERE title = ?", Long.class, title);
    }

    private void insertRegistration(Long userId, int rating, int eligible) {
        jdbc.update("INSERT INTO contest_user(contest_id, user_id, status, rating_at_registration, eligible) "
                + "VALUES (?, ?, 'AUTO_APPROVED', ?, ?)", contestId, userId, rating, eligible);
    }

    private void insertSubmission(Long userId, int solved, int penalty) {
        LocalDateTime start = LocalDateTime.now().minusHours(2);
        jdbc.update("INSERT INTO submission(problem_id, user_id, language, status, score, run_time, run_memory, "
                        + "create_time, judge_phase, contest_id) VALUES (1, ?, 'cpp', 1, 100, ?, 100, ?, 'FINISHED', ?)",
                userId, solved * 10, start, contestId);
    }

    private void insertRank(Long userId, int rank, int solved, int penalty, int score, String type) {
        jdbc.update("INSERT INTO contest_rank(contest_id, user_id, rank_num, solved_count, penalty_time, total_score, "
                        + "participant_type, is_visible, is_deleted) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 0)",
                contestId, userId, rank, solved, penalty, score, type);
    }

    private void insertVpAttempt(Long userId, int solved, int penalty) {
        LocalDateTime start = LocalDateTime.now().minusHours(2);
        jdbc.update("INSERT INTO vp_attempt(contest_id, user_id, status, start_time, end_time, submit_time, "
                        + "score, solved_count, penalty_time, rank_num, total_participants) "
                        + "VALUES (?, ?, 'FINISHED', ?, ?, ?, 0, ?, ?, 1, 1)",
                contestId, userId, start, start.plusHours(1), LocalDateTime.now().minusHours(1), solved, penalty);
    }
}
