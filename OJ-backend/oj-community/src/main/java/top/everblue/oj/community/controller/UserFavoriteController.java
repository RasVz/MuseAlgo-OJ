package top.everblue.oj.community.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.api.problem.ProblemListView;
import top.everblue.oj.api.submission.PageResult;
import top.everblue.oj.community.favorite.FavoriteApplicationService;
import top.everblue.oj.community.favorite.FavoriteCommand;
import top.everblue.oj.community.favorite.FavoriteTargetType;

import java.util.List;
import java.util.Map;

/**
 * 收藏功能的对外接口（收藏/取消、收藏列表）。
 * <p>
 * 本类不含业务逻辑，全部委托给
 * {@link top.everblue.oj.community.favorite.FavoriteApplicationService}——
 * 收藏目标横跨题目、题单、比赛、动态四类，各类的可收藏性校验与列表组装
 * 都不同，放在应用服务里按 {@link top.everblue.oj.community.favorite.FavoriteTargetType} 分派，
 * Controller 只负责取登录态。
 * </p>
 * <p>
 * 所有接口对未登录请求返回 401 错误码而不是抛异常，保持与社区其他 Controller 一致。
 * </p>
 */
@RestController
@RequestMapping("/favorite")
@RequiredArgsConstructor
public class UserFavoriteController {

    private final FavoriteApplicationService favorites;

    @GetMapping("/problem_list/list")
    public Result<PageResult<ProblemListView>> getFavoriteProblemLists(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String keyword) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return Result.error(401, "请先登录");
        return Result.success(favorites.favoriteProblemLists(userId, page, size, keyword));
    }

    @GetMapping("/posts/list")
    public Result<List<Map<String, Object>>> getFavoritePosts(@RequestParam(defaultValue = "0") Integer type) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return Result.error(401, "请先登录");
        return Result.success(favorites.favoritePosts(userId, type));
    }

    @PostMapping("/toggle")
    public Result<Boolean> toggleFavorite(@RequestBody Map<String, Object> params) {
        return change(params, "toggle");
    }

    @PostMapping("/add")
    public Result<String> addFavorite(@RequestBody Map<String, Object> params) {
        Result<Boolean> result = change(params, "activate");
        return result.getCode() == 200 ? Result.success("收藏成功") : Result.error(result.getCode(), result.getMessage());
    }

    @PostMapping("/remove")
    public Result<String> removeFavorite(@RequestBody Map<String, Object> params) {
        Result<Boolean> result = change(params, "deactivate");
        return result.getCode() == 200 ? Result.success("已取消收藏") : Result.error(result.getCode(), result.getMessage());
    }

    @GetMapping("/batch-check")
    public Result<Map<Long, Boolean>> batchCheck(@RequestParam List<Long> targetIds, @RequestParam String type) {
        try {
            return Result.success(favorites.activeStates(SecurityUtils.getUserId(), FavoriteTargetType.from(type), targetIds));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/counts")
    public Result<Map<String, Integer>> counts() {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return Result.error(401, "请先登录");
        return Result.success(favorites.activeCounts(userId));
    }

    @GetMapping("/check")
    public Result<Boolean> checkFavorite(@RequestParam Long targetId,
                                         @RequestParam(required = false) String type,
                                         @RequestParam(required = false) Integer targetType) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return Result.success(false);
        try {
            return Result.success(favorites.isActive(new FavoriteCommand(userId, targetId,
                    FavoriteTargetType.from(targetType != null ? targetType : type))));
        } catch (IllegalArgumentException e) {
            return Result.success(false);
        }
    }

    private Result<Boolean> change(Map<String, Object> params, String operation) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return Result.error(401, "请先登录");
        try {
            FavoriteCommand command = FavoriteCommand.from(userId, params);
            boolean active = switch (operation) {
                case "activate" -> favorites.activate(command);
                case "deactivate" -> favorites.deactivate(command);
                default -> favorites.toggle(command);
            };
            return Result.success(active);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }
}
