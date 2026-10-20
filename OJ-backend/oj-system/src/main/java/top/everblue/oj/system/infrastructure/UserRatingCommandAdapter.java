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
    public List<RatingEntry> findTopRatings(int limit) {
        if(limit<=0)return List.of();
        return userService.list(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                .isNotNull(User::getRating).orderByDesc(User::getRating).orderByAsc(User::getId)
                .last("LIMIT "+Math.min(limit,100))).stream().map(u->new RatingEntry(u.getId(),u.getRating())).toList();
    }
    @Override
    public Long findRank(Long userId) {
        var user=userId==null?null:userService.getById(userId);
        if(user==null || user.getRating()==null)return -1L;
        return 1+userService.count(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                .and(q->q.gt(User::getRating,user.getRating()).or(w->w.eq(User::getRating,user.getRating()).lt(User::getId,userId))));
    }

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
