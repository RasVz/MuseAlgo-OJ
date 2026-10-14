package top.everblue.oj.system.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.user.UserRatingCommand;
import top.everblue.oj.system.entity.User;
import top.everblue.oj.system.service.UserService;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户 Rating 契约适配器 — oj-system 侧实现 {@link UserRatingCommand}。
 */
@Service
@RequiredArgsConstructor
public class UserRatingCommandAdapter implements UserRatingCommand {

    private final UserService userService;

    @Override
    public Map<Long, Integer> findRatings(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<User> users = userService.listByIds(userIds);
        Map<Long, Integer> ratings = new LinkedHashMap<>();
        for (User user : users) {
            if (user.getRating() != null) {
                ratings.put(user.getId(), user.getRating());
            }
        }
        return ratings;
    }

    @Override
    public void batchUpdateRatings(Map<Long, Integer> userIdToRating) {
        if (userIdToRating == null || userIdToRating.isEmpty()) {
            return;
        }
        userIdToRating.forEach((userId, rating) -> {
            User user = new User();
            user.setId(userId);
            user.setRating(rating);
            userService.updateById(user);
        });
    }
}
