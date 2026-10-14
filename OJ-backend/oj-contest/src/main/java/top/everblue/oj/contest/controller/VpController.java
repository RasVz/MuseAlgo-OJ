package top.everblue.oj.contest.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.exception.ForbiddenException;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.contest.entity.vo.CorrectionVO;
import top.everblue.oj.contest.entity.vo.VpAttemptVO;
import top.everblue.oj.contest.service.VpAttemptService;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 虚拟赛（VP）与赛后订正追踪（B4）。
 * <p>
 * 四个端点全部只操作「当前登录用户自己的」VP 会话，用户 ID 一律由
 * {@link #requireUserId()} 从登录态取出、<b>绝不从请求参数接收</b>——
 * 否则任意用户都能替他人发起或结束虚拟赛。
 * </p>
 * <p>
 * 另有独立于 VP 的「订正追踪」端点：它回答的是赛后某题是否补做出来了，
 * 与是否发起过 VP 无关，因此放在同一 Controller 但不依赖 VP 会话存在。
 * </p>
 */
@Tag(name = "虚拟赛", description = "错过比赛按真实规则补打 + 赛后订正追踪")
@RestController
@RequestMapping("/contest")
@RequiredArgsConstructor
public class VpController {

    private final VpAttemptService vpAttemptService;

    @Operation(summary = "开始虚拟参赛")
    @PostMapping("/{contestId}/vp/start")
    public Result<VpAttemptVO> start(@PathVariable Long contestId) {
        return Result.success(vpAttemptService.start(contestId, requireUserId()));
    }

    @Operation(summary = "交卷（虚拟赛）")
    @PostMapping("/{contestId}/vp/finish")
    public Result<VpAttemptVO> finish(@PathVariable Long contestId) {
        return Result.success(vpAttemptService.finish(contestId, requireUserId()));
    }

    @Operation(summary = "我的虚拟赛状态")
    @GetMapping("/{contestId}/vp")
    public Result<VpAttemptVO> getMy(@PathVariable Long contestId) {
        return Result.success(vpAttemptService.getMy(contestId, requireUserId()));
    }

    @Operation(summary = "赛后订正追踪（每题是否在赛后 7 天内 AC）")
    @GetMapping("/{contestId}/correction")
    public Result<List<CorrectionVO>> correction(@PathVariable Long contestId) {
        return Result.success(vpAttemptService.correction(contestId, requireUserId()));
    }

    /**
 * 取出当前登录用户 ID，缺失时拒绝请求。
     * <p>
     * 用异常而非返回错误码，是为了让四个端点都保持「正常流程只有一条 return」的形态；
     * 全局异常处理器会把该异常转成统一的未授权响应。
     * </p>
     *
     * @return 当前登录用户 ID，绝不为 {@code null}
     * @throws ForbiddenException 未登录时抛出
     */
    private Long requireUserId() {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            throw new ForbiddenException("请先登录");
        }
        return userId;
    }
}
