package com.musealgo.oj.controller;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import com.musealgo.oj.contest.application.ContestLevelManagementApplicationService;
import com.musealgo.oj.contest.entity.ContestLevel;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"oj.messaging.rabbitmq-enabled=false", "oj.judge.worker.enabled=false"})
class ContestLevelManagementIT {
    @Autowired private ContestLevelManagementApplicationService levels;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.musealgo.oj.contest.application.ContestManagementApplicationService contests;
    private Long levelId;
    private Long contestId;
    @AfterEach void cleanup() {
        if (contestId != null) jdbc.update("DELETE FROM contest WHERE id=?", contestId);
        if (levelId != null) jdbc.update("DELETE FROM contest_level WHERE id=?", levelId);
        SecurityContextHolder.clearContext();
    }
    @Test void boundedLevelCanBecomeUnboundedWithoutClientOverwritingCreationTime() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var command = new ContestLevel(); command.setCode("it-"+UUID.randomUUID().toString().substring(0, 12)); command.setName("测试等级");
        command.setMinRating(1000); command.setMaxRating(1600);
        var created = levels.create(command); levelId = created.getId();
        command.setMinRating(null); command.setMaxRating(null); command.setCreateTime(java.time.LocalDateTime.of(2000,1,1,0,0));
        var updated = levels.update(levelId, command);
        assertNull(updated.getMinRating()); assertNull(updated.getMaxRating());
        assertNotEquals(command.getCreateTime(), updated.getCreateTime());
        assertThrows(IllegalArgumentException.class, () -> levels.update(Long.MAX_VALUE, command));
    }
    @Test void clearingContestLevelPersistsUnboundedSnapshot() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                (com.musealgo.oj.common.security.AuthenticatedUser) () -> 1L, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var level = new ContestLevel(); level.setCode("it-"+UUID.randomUUID().toString().substring(0,12));
        level.setName("有界等级"); level.setMinRating(1000); level.setMaxRating(1600);
        levelId = levels.create(level).getId();
        var contest = new com.musealgo.oj.contest.entity.Contest();
        contest.setTitle("clear-level-"+UUID.randomUUID()); contest.setLevelCode(level.getCode());
        contest.setStartTime(java.time.LocalDateTime.now().plusHours(1)); contest.setEndTime(contest.getStartTime().plusHours(1));
        var created = contests.create(contest, 1L); contestId = created.getId();
        assertEquals(1000, jdbc.queryForObject("SELECT rating_min FROM contest WHERE id=?", Integer.class, contestId));
        created.setLevelCode(""); contests.update(created);
        var row = jdbc.queryForMap("SELECT level_code,rating_min,rating_max FROM contest WHERE id=?", contestId);
        assertNull(row.get("level_code")); assertNull(row.get("rating_min")); assertNull(row.get("rating_max"));
    }

}
