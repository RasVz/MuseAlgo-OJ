package com.musealgo.oj.system.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.musealgo.oj.api.user.UserStatsCommand;
import com.musealgo.oj.system.service.UserService;

/**
 * 用户社交计数契约适配器 — oj-system 侧实现 {@link UserStatsCommand}。
 */
@Service
@RequiredArgsConstructor
public class UserStatsCommandAdapter implements UserStatsCommand {

    private final UserService userService;

    @Override
    public void incrementFollowerCount(Long userId, int delta) {
        userService.incrementFollowerCount(userId, delta);
    }

    @Override
    public void incrementFollowingCount(Long userId, int delta) {
        userService.incrementFollowingCount(userId, delta);
    }

    @Override
    public void incrementSubmissionCount(Long userId) {
        userService.incrementSubmitCount(userId);
    }

    @Override
    public void incrementAcceptedProblemCount(Long userId) {
        userService.incrementAcceptedCount(userId);
    }

    @Override
    public void recalculateCounters(Long userId) {
        userService.recalculateCounters(userId);
    }
}
