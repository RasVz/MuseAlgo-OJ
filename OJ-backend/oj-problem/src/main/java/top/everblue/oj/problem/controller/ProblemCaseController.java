package top.everblue.oj.problem.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.problem.application.ProblemManagementApplicationService;
import top.everblue.oj.problem.application.TestDataGenerationService;
import top.everblue.oj.problem.entity.ProblemCase;
import top.everblue.oj.problem.entity.vo.ProblemCaseVO;
import top.everblue.oj.problem.importer.FpsTestCase;

import java.util.List;
import java.util.Map;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 判题用例表 前端控制器
 */
@Tag(name = "测试用例管理", description = "测试用例的导入与查询")
@RestController
@RequiredArgsConstructor
@RequestMapping("/problem/case")
public class ProblemCaseController {
    private final ProblemManagementApplicationService problemManagementApplicationService;
    private final TestDataGenerationService testDataGenerationService;

    /**
     * 上传测试用例 Zip 包
     * 前端传参：file (二进制文件), problemId (Long)
     */
    @Operation(summary = "上传测试用例 Zip 包")
    @PostMapping("/upload")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result uploadCases(@RequestParam("file") MultipartFile file,
                              @RequestParam("problemId") Long problemId) {
        int count = problemManagementApplicationService.importCases(problemId, file);
        return Result.success("成功导入 " + count + " 组测试用例");
    }

    /**
     * 获取指定题目的所有测试用例列表（含输入/输出内容，超长截断）
     * 对应前端 api: getProblemCases(problemId)
     */
    @Operation(summary = "获取指定题目的测试用例列表（含内容预览）")
    @GetMapping("/list")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result getProblemCases(@RequestParam("problemId") Long problemId) {
        // 参数校验
        if (problemId == null) {
            return Result.error("题目ID不能为空");
        }

        List<ProblemCaseVO> list = problemManagementApplicationService.getPrivateCases(problemId);
        return Result.success(list);
    }

    /** 删除单个测试用例 */
    @Operation(summary = "删除单个测试用例（剩余用例自动重排并重算分值）")
    @PostMapping("/delete/{caseId}")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result deleteCase(@PathVariable Long caseId) {
        problemManagementApplicationService.deleteCase(caseId);
        return Result.success("测试用例已删除");
    }

    /** 清空某题全部测试用例 */
    @Operation(summary = "清空某题全部测试用例（文件与记录，计数归零）")
    @PostMapping("/clear")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result clearCases(@RequestBody Map<String, Long> body) {
        if (body == null || body.get("problemId") == null) {
            return Result.error("题目ID不能为空");
        }
        problemManagementApplicationService.clearCases(body.get("problemId"));
        return Result.success("该题测试用例已全部清空");
    }

    /** 命题工作台：批量保存测试用例请求体 */
    public record BatchCaseItem(String input, String output) {}

    public record BatchCasesRequest(Long problemId, List<BatchCaseItem> cases) {}

    /**
     * 批量保存测试用例（JSON 直存，命题工作台用；覆盖旧用例，与 ZIP 导入语义一致）
     */
    @Operation(summary = "批量保存测试用例（JSON 直存，覆盖旧用例）")
    @PostMapping("/batch")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result batchSaveCases(@RequestBody BatchCasesRequest request) {
        if (request == null || request.problemId() == null) {
            return Result.error("题目ID不能为空");
        }
        if (request.cases() == null || request.cases().isEmpty()) {
            return Result.error("测试用例不能为空");
        }
        List<FpsTestCase> items = request.cases().stream()
                .map(c -> new FpsTestCase(c.input(), c.output()))
                .toList();
        int count = problemManagementApplicationService.saveCases(request.problemId(), items);
        return Result.success("成功保存 " + count + " 组测试用例");
    }

    /** 生成并验证请求体：生成器代码（Python，AI 生成可人工修改） */
    public record GenerateVerifyRequest(Long problemId, String generatorCode) {}

    /**
     * 生成并验证测试数据（AI 题库工具，生成器模式）：
     * 在 Judge0 沙箱执行 Python 生成器 → 按 ===CASE=== 切分多组输入 →
     * 逐组用题目已保存的 std 实测 → 返回通过/失败统计。不落库，前端确认后走追加保存。
     */
    @Operation(summary = "生成并验证测试数据（执行生成器 + std 逐组实测，不落库）")
    @PostMapping("/generate-and-verify")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result generateAndVerify(@RequestBody GenerateVerifyRequest request) {
        if (request == null || request.problemId() == null) {
            return Result.error("题目ID不能为空");
        }
        var result = testDataGenerationService.generateAndVerify(
                request.problemId(), request.generatorCode());
        return Result.success(result);
    }

    /** 追加保存请求体（不覆盖旧用例） */
    public record AppendCasesRequest(Long problemId, List<BatchCaseItem> cases) {}

    /**
     * 追加保存测试用例（AI 题库工具）：按输入内容去重，不覆盖旧用例；
     * 追加后重新均分分值并更新总条数。
     */
    @Operation(summary = "追加保存测试用例（按输入去重，不覆盖旧用例）")
    @PostMapping("/append")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result appendCases(@RequestBody AppendCasesRequest request) {
        if (request == null || request.problemId() == null) {
            return Result.error("题目ID不能为空");
        }
        if (request.cases() == null || request.cases().isEmpty()) {
            return Result.error("测试用例不能为空");
        }
        List<FpsTestCase> items = request.cases().stream()
                .map(c -> new FpsTestCase(c.input(), c.output()))
                .toList();
        var result = problemManagementApplicationService.appendCases(request.problemId(), items);
        return Result.success(result);
    }
}
