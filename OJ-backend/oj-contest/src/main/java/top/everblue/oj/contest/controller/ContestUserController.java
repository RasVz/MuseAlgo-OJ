package top.everblue.oj.contest.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.contest.service.ContestUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 比赛报名端点。
 * <p>
 * 只暴露「报名」一个动作：报名成功后是否自动加入参赛、能否改报、能否取消报名，
 * 这些都由服务端规则决定，不接受前端传入用户 ID。
 * </p>
 */
@Tag(name = "比赛报名", description = "报名、取消报名与参赛查询")
@RestController
@RequestMapping("/contest")
@RequiredArgsConstructor
public class ContestUserController {
    private final ContestUserService contestUserService;

    @Operation(summary = "报名比赛")
    @PostMapping("/register/{contestId}")
    public Result<String> register(@PathVariable Long contestId) {
        // 身份取自登录态（SecurityUtils 读 SecurityContext），不接受前端传参
        Long userId = SecurityUtils.getUserId();

        try {
            contestUserService.registerContest(contestId, userId);
            return Result.success("报名成功！");
        } catch (Exception e) {
            // 捕获异常（比如重复报名的异常），返回给前端
            // 把 e.getMessage() 直接回显是有意为之：服务层的业务异常消息本身就是写给用户看的文案；
            // 相应地服务层不得在异常消息里放入内部细节（SQL、堆栈、其它用户数据）
            return Result.error(e.getMessage());
        }
    }
}
