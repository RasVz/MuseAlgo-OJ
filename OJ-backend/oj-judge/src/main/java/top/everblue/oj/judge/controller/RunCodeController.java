package top.everblue.oj.judge.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.judge.service.RunCodeService;

import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 在线运行调试接口：把一段代码连同用户手填的输入丢给判题机，返回输出。
 * <p>
 * <strong>不落库、不计入提交历史</strong>，这是它与 {@link SubmissionController} 的根本区别——
 * 调试是高频操作，学生会连点十几次，若每次都写一条 submission，提交记录会被调试行为淹没，
 * 统计口径（AC 数、通过率）也会失真。
 * </p>
 * <p>
 * 之所以要在这里把异常转成 {@code Result.error} 而不完全交给全局异常处理器：调试失败是<strong>预期内的正常结果</strong>
 * （语言不支持、编译错误、判题机超时），前端需要区分「代码跑不通」和「系统出问题了」并给出不同提示。
 * 语言不支持走 {@code IllegalArgumentException} 分支返回原始文案，其余异常加「运行代码失败：」前缀，
 * 前端据此区分两类问题。
 * </p>
 * <p>
 * 这里是少数<strong>没有限流</strong>的写类接口：因为它绕过数据库，滥用压力全部落在判题机 CPU 上。
 * 如果 Judge0 与应用同机部署，请确认判题机自身的并发上限或补上 {@code @RateLimit}，
 * 否则一个写死循环脚本就能拖垮全站判题。
 * </p>
 */
@Tag(name = "在线运行", description = "代码在线运行调试")
@RestController
@RequiredArgsConstructor
@RequestMapping("/problem")
public class RunCodeController {

    private final RunCodeService runCodeService;

    @Operation(summary = "在线运行代码")
    @PostMapping("/run")
    public Result<Map<String, Object>> runCode(@RequestBody Map<String, String> params) {
        String code = params.get("code");
        String language = params.get("language");
        String input = params.get("input");

        if (code == null || code.trim().isEmpty()) {
            return Result.error("代码不能为空");
        }

        try {
            Map<String, Object> result = runCodeService.executeCode(code, language, input == null ? "" : input);
            return Result.success(result);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            return Result.error("运行代码失败：" + e.getMessage());
        }
    }
}
