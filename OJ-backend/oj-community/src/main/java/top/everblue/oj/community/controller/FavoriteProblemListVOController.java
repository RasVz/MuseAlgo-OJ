package top.everblue.oj.community.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.community.favorite.FavoriteApplicationService;

import java.util.LinkedHashMap;
import java.util.Map;

/** Compatibility adapter for the legacy favorite-problem-list view URL. */
@RestController
@RequestMapping("/favorite/problem_list")
@RequiredArgsConstructor
public class FavoriteProblemListVOController {
    private final FavoriteApplicationService favorites;

    @GetMapping("/vo/list")
    public Map<String, Object> getFavoriteProblemListVO(@RequestParam(defaultValue = "1") Long page,
                                                         @RequestParam(defaultValue = "12") Long size,
                                                         @RequestParam(required = false) String keyword) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return response(401, "请先登录", null);
        return response(200, "success", favorites.favoriteProblemListView(userId, page, size, keyword));
    }

    private Map<String, Object> response(int code, String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", code); response.put("message", message); response.put("data", data);
        return response;
    }
}
