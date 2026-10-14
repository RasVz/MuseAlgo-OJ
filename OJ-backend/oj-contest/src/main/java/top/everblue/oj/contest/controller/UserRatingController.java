package top.everblue.oj.contest.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.contest.entity.vo.UserRatingVO;
import top.everblue.oj.contest.service.UserRatingService;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 评分走势端点。
 * <p>
 * 接口按 {@code userId} 而非「当前登录用户」暴露，因为评分曲线是<b>公开的荣誉展示</b>，
 * 查看他人的曲线不需要权限；只有「修改评分」的入口才需要鉴权，而那不在本Controller 内。
 * </p>
 */
@Tag(name = "Rating 系统", description = "用户 Rating 历史与走势")
@RestController
@RequestMapping("/user/rating")
@RequiredArgsConstructor
public class UserRatingController {

    private final UserRatingService userRatingService;

    /**
     * 获取用户 Rating 变化历史曲线
     * @param userId 用户ID
     * @return 历史记录列表
     */
    @Operation(summary = "获取用户 Rating 变化历史")
    @GetMapping("/history/{userId}")
    public Result<List<UserRatingVO>> getRatingHistory(@PathVariable Long userId) {
        List<UserRatingVO> history = userRatingService.getRatingHistory(userId);
        return Result.success(history);
    }
}