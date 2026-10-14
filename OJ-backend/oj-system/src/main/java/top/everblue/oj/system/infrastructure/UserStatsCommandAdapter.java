package top.everblue.oj.system.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.user.UserStatsCommand;
import top.everblue.oj.system.service.UserService;

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
}
