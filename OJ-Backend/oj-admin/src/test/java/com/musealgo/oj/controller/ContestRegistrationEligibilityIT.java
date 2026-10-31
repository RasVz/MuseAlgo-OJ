package com.musealgo.oj.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.musealgo.oj.api.contest.ContestSubmissionContextResolver;
import com.musealgo.oj.contest.entity.ContestUser;
import com.musealgo.oj.contest.service.ContestUserService;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T10-1 真实 MySQL 验收：正式赛程提交的报名门槛（审核开启 → 报名/审核通过后才可提交）、
 * 报名时 Rating 快照与超限打星号（eligible=0）。
 */
@SpringBootTest(properties = {"oj.messaging.rabbitmq-enabled=false", "oj.judge.worker.enabled=false"})
class ContestRegistrationEligibilityIT {

    @Autowired private ContestUserService contestUserService;
    @Autowired private ContestSubmissionContextResolver resolver;
    @Autowired private JdbcTemplate jdbc;

    private Long userId;
    private Long contestId;

    @AfterEach
    void cleanup() {
        if (contestId != null) {
            jdbc.update("DELETE FROM contest_user WHERE contest_id = ?", contestId);
            jdbc.update("DELETE FROM problem_contest WHERE contest_id = ?", contestId);
            jdbc.update("DELETE FROM contest WHERE id = ?", contestId);
        }
        if (userId != null) {
            jdbc.update("DELETE FROM user WHERE id = ?", userId);
        }
    }

    @Test
    void reviewRequiredContestGatesSubmissionUntilApprovedAndSnapshotsOverLimitRating() {
        userId = insertUser(1900);
        contestId = insertContest(true, 1000, 1800);
        jdbc.update("INSERT INTO problem_contest(contest_id, problem_id, problem_order, score, visible, is_deleted) "
                + "VALUES (?, 1, 1, 100, 1, 0)", contestId);

        // 未报名 → 拒绝
        IllegalStateException notRegistered = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(contestId, 1L, userId));
        assertTrue(notRegistered.getMessage().contains("报名"));

        // 报名：审核中、Rating 快照 1900、超过上限 → eligible=0（打星号）
        ContestUser registration = contestUserService.registerContest(contestId, userId);
        assertEquals("PENDING", registration.getStatus());
        assertEquals(1900, registration.getRatingAtRegistration());
        assertEquals(0, registration.getEligible());

        // 审核中仍然拒绝
        assertThrows(IllegalStateException.class, () -> resolver.resolve(contestId, 1L, userId));

        // 审核通过后允许提交；资格超限只影响正式榜，不阻止提交
        contestUserService.reviewRegistration(contestId, userId, true, null, 1L);
        var context = resolver.resolve(contestId, 1L, userId);
        assertEquals(contestId, context.contestId());
        assertNull(context.vpAttemptId());
    }

    private Long insertUser(int rating) {
        String username = "t10_it_" + UUID.randomUUID();
        jdbc.update("INSERT INTO user(username, password, nickname, rating) VALUES (?, 'test-only', ?, ?)",
                username, username, rating);
        return jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }

    private Long insertContest(boolean review, int min, int max) {
        String title = "t10-it-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO contest(title, description, start_time, end_time, duration, type, mode, category, "
                        + "registration_review, rating_min, rating_max, author_id, author_name, visible, is_deleted) "
                        + "VALUES (?, 't10', ?, ?, 60, 'Public', 'ACM', 'FORMAL', ?, ?, ?, 1, 'it', 1, 0)",
                title, now.minusHours(1), now.plusHours(1), review ? 1 : 0, min, max);
        return jdbc.queryForObject("SELECT id FROM contest WHERE title = ?", Long.class, title);
    }
}
