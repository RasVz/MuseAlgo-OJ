package top.everblue.oj.contest.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.everblue.oj.common.api.Result; // 你的统一返回结果类
import top.everblue.oj.common.utils.SecurityUtils; // 你的获取当前用户工具类
import top.everblue.oj.contest.service.RankService;
import top.everblue.oj.contest.entity.vo.RankVO;

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
        try {
            // 1. 获取当前登录用户 ID
            Long userId = SecurityUtils.getUserId();

            // 2. 如果没登录，返回 -1
            if (userId == null) {
                return Result.success(-1L);
            }

            // 3. 查询数据库当前名次
            Long rank = rankService.getMyRank(userId);
            return Result.success(rank);

        } catch (Exception e) {
            // 应对 SecurityUtils 在未登录时可能抛异常的情况
            // 未登录返回 -1 而非 4xx：这个入口是首页侧栏的展示位，
            // 未登录时也要正常渲染页面，抛错会让整个首页报错；
            // -1 表示「未上榜」，前端据此隐藏名次展示即可
            return Result.success(-1L);
        }
    }
}