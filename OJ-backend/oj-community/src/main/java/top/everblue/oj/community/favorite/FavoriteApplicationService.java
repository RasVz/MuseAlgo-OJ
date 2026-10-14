package top.everblue.oj.community.favorite;

import top.everblue.oj.api.problem.ProblemListView;
import top.everblue.oj.api.submission.PageResult;
import top.everblue.oj.community.entity.vo.UserFavoriteVO;

import java.util.List;
import java.util.Map;

/** Deep module for favorite state, target-specific counters, and favorite queries. */
public interface FavoriteApplicationService {
    boolean activate(FavoriteCommand command);

    boolean deactivate(FavoriteCommand command);

    boolean toggle(FavoriteCommand command);

    boolean isActive(FavoriteCommand command);

    Map<Long, Boolean> activeStates(Long userId, FavoriteTargetType targetType, List<Long> targetIds);

    Map<String, Integer> activeCounts(Long userId);

    PageResult<ProblemListView> favoriteProblemLists(Long userId, Integer page, Integer size, String keyword);

    List<Map<String, Object>> favoritePosts(Long userId, Integer postType);

    Map<String, Object> favoriteProblemListView(Long userId, Long page, Long size, String keyword);
}
