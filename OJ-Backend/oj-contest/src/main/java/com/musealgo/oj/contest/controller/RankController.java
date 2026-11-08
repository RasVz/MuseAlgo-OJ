package com.musealgo.oj.contest.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.musealgo.oj.common.api.Result; // 你的统一返回结果类
import com.musealgo.oj.common.utils.SecurityUtils; // 你的获取当前用户工具类
import com.musealgo.oj.contest.service.RankService;
import com.musealgo.oj.contest.entity.vo.RankVO;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 全站总排行榜端点（{@link RankVO} 那一套），与比赛内榜单的端点完全无关。
 * <p>
 * 两个排行榜分属不同 Controller：比赛实时榜在 {@link ContestRankController}，
 * SSE 推送在 {@link ContestRankingSseController}。路由前缀也不同（{@code /rank} vs {@code /contest}），
 * 前端按路径区分，不要混淆两套名次语义。
 * </p>
 */
@Tag(name = "比赛排名", description = "实时排名与最终排名查询")
@RestController
@RequestMapping("/rank")
@RequiredArgsConstructor
public class RankController {

    private final RankService rankService;

    /**
     * 获取全站排行榜 (默认取前 50 名)
     * 接口路径: GET /rank/list
     */
    @Operation(summary = "获取全站排行榜")
    @GetMapping("/list")
    public Result<List<RankVO>> getRankList(@RequestParam(defaultValue = "50") int limit) {
        // 限制一下最大查询数量，防止前端传个 10000 造成过大的数据库查询
        // 必须服务端设上限：limit 是前端可控参数，不设防就等于开放了任意深度的榜单遍历
        if (limit > 100) {
            limit = 100;
        }
        List<RankVO> list = rankService.getTopRank(limit);
        return Result.success(list);
    }

    /**
     * 获取“我的”当前排名
     * 接口路径: GET /rank/my
     */
    @Operation(summary = "我的当前排名")
    @GetMapping("/my")
    public Result<Long> getMyRank() {
        Long userId = SecurityUtils.getUserId();
        // 首页匿名展示仍使用 -1；真实查询故障交给统一异常处理。
        if (userId == null) return Result.success(-1L);
        return Result.success(rankService.getMyRank(userId));
    }
}
