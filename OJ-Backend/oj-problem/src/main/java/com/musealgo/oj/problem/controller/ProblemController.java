package com.musealgo.oj.problem.controller;

import com.musealgo.oj.common.utils.RequestValues;

import com.musealgo.oj.common.exception.ErrorResponses;
import com.musealgo.oj.common.exception.CommonErrorCode;
import com.musealgo.oj.problem.error.ProblemErrorCode;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.common.annotation.AuditLog;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.problem.entity.Problem;
import com.musealgo.oj.problem.entity.ProblemCase;
import com.musealgo.oj.problem.entity.dto.ProblemDTO;
import com.musealgo.oj.problem.entity.vo.ProblemCaseVO;
import com.musealgo.oj.problem.entity.vo.ProblemDetailVO;
import com.musealgo.oj.problem.entity.vo.ProblemVO;
import com.musealgo.oj.problem.entity.vo.ProblemFilterOptionsVO;
import com.musealgo.oj.problem.format.ProblemFormatApplyRequest;
import com.musealgo.oj.problem.format.ProblemFormatApplyResult;
import com.musealgo.oj.problem.format.ProblemFormatJobRequest;
import com.musealgo.oj.problem.format.ProblemFormatJobVO;
import com.musealgo.oj.problem.format.ProblemFormatPreviewItem;
import com.musealgo.oj.problem.format.ProblemFormatRequest;
import com.musealgo.oj.problem.format.StatementComposeRequest;
import com.musealgo.oj.problem.format.StatementOptimizeRequest;
import com.musealgo.oj.problem.format.StatementVersionApplyRequest;
import com.musealgo.oj.problem.format.StatementVersionDetailVO;
import com.musealgo.oj.problem.format.StatementVersionVO;
import com.musealgo.oj.problem.importer.FpsImportResult;
import com.musealgo.oj.problem.importer.FpsImportService;
import com.musealgo.oj.problem.application.CaseSetChangedException;
import com.musealgo.oj.problem.application.ProblemFormatJobService;
import com.musealgo.oj.problem.application.ProblemManagementApplicationService;
import com.musealgo.oj.problem.application.ProblemSearchService;
import com.musealgo.oj.problem.application.ProblemStatementVersionService;
import com.musealgo.oj.problem.application.TestDataGenerationService;
import com.musealgo.oj.problem.infrastructure.ProblemSearchIndexer;
import com.musealgo.oj.problem.service.ProblemService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 题目管理接口。
 * <p>
 * 仅做参数接收与结果包装，<b>不承载业务逻辑也不直接操作题目表</b>：
 * 写操作一律转交 {@link ProblemManagementApplicationService}，
 * 检索转交 {@link ProblemSearchService}。
 * 这样保证权限校验、事务边界只有一处实现，不会出现"某个接口忘了校验"。
 * </p>
 * <p>
 * 方法上的 {@code @Operation} 已说明各接口用途，按规范不再重复写 Javadoc。
 * </p>
 */
@Tag(name = "题目管理", description = "题目的增删改查与列表检索")
@RestController
@RequiredArgsConstructor
@RequestMapping("/problem")
public class ProblemController {

    private final ProblemService problemService;
    private final ProblemManagementApplicationService appService;
    private final FpsImportService fpsImportService;
    private final ProblemSearchService problemSearchService;
    private final TestDataGenerationService testDataGenerationService;
    private final ProblemFormatJobService formatJobService;
    private final ProblemStatementVersionService statementVersionService;

    @Operation(summary = "新增题目")
    @AuditLog(module = "problem", action = "create_problem")
    @PostMapping("/add")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<Long> addProblem(@RequestBody ProblemDTO dto) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return ErrorResponses.failure(CommonErrorCode.AUTH_REQUIRED, "请先登录");
        return Result.success(appService.create(dto, userId));
    }

    @Operation(summary = "获取比赛题目列表")
    @GetMapping("/contest/{contestId}")
    public Result<List<ProblemDetailVO>> getProblemsByContest(@PathVariable Long contestId) {
        return Result.success(problemService.list(
                new LambdaQueryWrapper<Problem>().eq(Problem::getContestId, contestId).orderByAsc(Problem::getId))
                .stream().map(problem -> appService.getDetail(problem.getId())).toList());
    }

    @Operation(summary = "题目列表（分页检索）")
    @GetMapping("/list")
    public Result<Page<ProblemVO>> getProblemList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            String keyword, Integer difficulty,
            @RequestParam(required = false) Integer minDifficulty,
            @RequestParam(required = false) Integer maxDifficulty,
            String tag,
            @RequestParam(required = false) String tags,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Integer userStatus,
            @RequestParam(required = false) Long userId) {
        return Result.success(problemService.getProblemVOPage(
                page, size, keyword, difficulty, minDifficulty, maxDifficulty,
                tag, tags, source, userStatus, userId));
    }

    @Operation(summary = "题库筛选选项")
    @GetMapping("/filter-options")
    public Result<ProblemFilterOptionsVO> getProblemFilterOptions() {
        return Result.success(problemService.getProblemFilterOptions());
    }

    @Operation(summary = "全文搜索（Meilisearch 召回 + DB 回查，故障自动回退模糊查询）")
    @GetMapping("/search")
    public Result<Page<ProblemVO>> searchProblems(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            String keyword,
            Integer difficulty,
            @RequestParam(required = false) Integer minDifficulty,
            @RequestParam(required = false) Integer maxDifficulty) {
        Long userId = SecurityUtils.getUserId();
        return Result.success(problemSearchService.search(keyword, page, size, difficulty,
                minDifficulty, maxDifficulty, userId));
    }

    @Operation(summary = "重建搜索索引（管理员手动触发）")
    @GetMapping("/admin/search/reindex")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<String> reindexSearchIndex() {
        ProblemSearchIndexer.ReindexResult result = problemSearchService.reindexAll();
        return Result.success("搜索索引重建完成：已提交 " + result.indexed() + " / " + result.total() + " 道题目");
    }

    @Operation(summary = "随机推荐题目")
    @GetMapping("/recommendations/random")
    public Result<List<Problem>> getRandomRecommended(@RequestParam(defaultValue = "3") Integer limit) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return ErrorResponses.failure(CommonErrorCode.AUTH_REQUIRED, "请先登录");
        return Result.success(problemService.getRandomUnsolvedPublicProblems(userId, limit));
    }

    @Operation(summary = "我创建的题目列表")
    @GetMapping("/my-created/list")
    public Result<Page<Map<String, Object>>> getMyCreatedProblemList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer difficulty,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Integer privacy,
            @RequestParam(required = false) Boolean isPublic,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "ascending") String sortOrder) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return ErrorResponses.failure(CommonErrorCode.AUTH_REQUIRED, "未登录");
        return Result.success(appService.getMyCreatedProblemList(
                userId, page, 50, keyword, difficulty, tag, privacy, isPublic, sortBy, sortOrder));
    }

    @Operation(summary = "题库管理全量列表（管理员）")
    @GetMapping("/admin/list")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Page<Map<String, Object>>> adminList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer difficulty,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Integer privacy,
            @RequestParam(required = false) Boolean isPublic,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "ascending") String sortOrder) {
        return Result.success(appService.adminList(
                page, 50, keyword, difficulty, tag, privacy, isPublic, sortBy, sortOrder));
    }

    @Operation(summary = "题面版本列表（含 LIVE/历史/待审候选）")
    @GetMapping("/{problemId}/statement/versions")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<List<StatementVersionVO>> listStatementVersions(@PathVariable Long problemId) {
        return Result.success(statementVersionService.list(problemId));
    }

    @Operation(summary = "题面版本详情")
    @GetMapping("/{problemId}/statement/versions/{versionId}")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<StatementVersionDetailVO> getStatementVersion(@PathVariable Long problemId,
                                                                @PathVariable Long versionId) {
        return Result.success(statementVersionService.detail(problemId, versionId));
    }

    @Operation(summary = "候选版本的审阅数据（原文取基线版本，不调用 AI）")
    @GetMapping("/{problemId}/statement/versions/{versionId}/proposal")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<ProblemFormatPreviewItem> getStatementVersionProposal(@PathVariable Long problemId,
                                                                        @PathVariable Long versionId) {
        return Result.success(statementVersionService.proposal(problemId, versionId));
    }

    @Operation(summary = "基于指定版本发起优化（异步任务，单题）")
    @AuditLog(module = "problem", action = "statement_optimize")
    @PostMapping("/{problemId}/statement/versions/optimize")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<ProblemFormatJobVO> optimizeStatement(@PathVariable Long problemId,
                                                        @RequestBody StatementOptimizeRequest request) {
        Long baseVersionId = request == null ? null : request.baseVersionId();
        boolean ipMode = request != null && request.ipEnabled();
        return Result.success(formatJobService.createVersionJob(problemId, baseVersionId, ipMode));
    }

    @Operation(summary = "保存编辑器内容为新版本（编辑与 AI 改写共用）")
    @AuditLog(module = "problem", action = "statement_compose")
    @PostMapping("/{problemId}/statement/versions/compose")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<StatementVersionVO> composeStatement(@PathVariable Long problemId,
                                                       @RequestBody StatementComposeRequest request) {
        return Result.success(statementVersionService.compose(problemId, request));
    }

    @Operation(summary = "把当前题面记录为新版本")
    @AuditLog(module = "problem", action = "statement_version_record")
    @PostMapping("/{problemId}/statement/versions/current")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<StatementVersionVO> recordCurrentStatement(@PathVariable Long problemId) {
        return Result.success(statementVersionService.recordCurrent(problemId));
    }

    @Operation(summary = "把历史版本设为题目当前版本")
    @AuditLog(module = "problem", action = "statement_version_activate")
    @PostMapping("/{problemId}/statement/versions/{versionId}/activate")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<StatementVersionVO> activateStatementVersion(@PathVariable Long problemId,
                                                                @PathVariable Long versionId) {
        return Result.success(statementVersionService.activate(problemId, versionId));
    }

    @Operation(summary = "应用候选版本（字段白名单）")
    @AuditLog(module = "problem", action = "statement_version_apply")
    @PostMapping("/{problemId}/statement/versions/{versionId}/apply")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<StatementVersionVO> applyStatementVersion(@PathVariable Long problemId,
                                                            @PathVariable Long versionId,
                                                            @RequestBody StatementVersionApplyRequest request) {
        return Result.success(statementVersionService.applyCandidate(problemId, versionId, request));
    }

    @Operation(summary = "丢弃候选版本")
    @AuditLog(module = "problem", action = "statement_version_discard")
    @DeleteMapping("/{problemId}/statement/versions/{versionId}")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<String> discardStatementVersion(@PathVariable Long problemId, @PathVariable Long versionId) {
        statementVersionService.discardCandidate(problemId, versionId);
        return Result.success("已丢弃");
    }

    @Operation(summary = "创建批量整理任务（异步，立即返回任务详情）")
    @AuditLog(module = "problem", action = "format_job_create")
    @PostMapping("/format/jobs")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<ProblemFormatJobVO> createFormatJob(@RequestBody ProblemFormatJobRequest request) {
        return Result.success(formatJobService.create(request));
    }

    @Operation(summary = "查询当前登录人正在进行的整理任务（无则返回 null）")
    @GetMapping("/format/jobs/active")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<ProblemFormatJobVO> getActiveFormatJob() {
        return Result.success(formatJobService.activeJob());
    }

    @Operation(summary = "查询整理任务进度与逐项结果")
    @GetMapping("/format/jobs/{jobId}")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<ProblemFormatJobVO> getFormatJob(@PathVariable Long jobId) {
        return Result.success(formatJobService.detail(jobId));
    }

    @Operation(summary = "读取任务内单题提案（不再调用 AI，用于人工审阅）")
    @GetMapping("/format/jobs/{jobId}/items/{problemId}")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<ProblemFormatPreviewItem> getFormatJobItem(@PathVariable Long jobId,
                                                             @PathVariable Long problemId) {
        return Result.success(formatJobService.proposal(jobId, problemId));
    }

    @Operation(summary = "取消整理任务")
    @AuditLog(module = "problem", action = "format_job_cancel")
    @PostMapping("/format/jobs/{jobId}/cancel")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<ProblemFormatJobVO> cancelFormatJob(@PathVariable Long jobId) {
        return Result.success(formatJobService.cancel(jobId));
    }

    @Operation(summary = "重试整理任务中的失败项")
    @AuditLog(module = "problem", action = "format_job_retry")
    @PostMapping("/format/jobs/{jobId}/retry")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<ProblemFormatJobVO> retryFormatJob(@PathVariable Long jobId) {
        return Result.success(formatJobService.retryFailed(jobId));
    }

    @Operation(summary = "单题整理预览（AI 整理但未保存；ipMode=true 时同一次调用完成 IP 改名）")
    @PostMapping("/format/preview")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<ProblemFormatPreviewItem> previewFormatProblem(@RequestBody ProblemFormatRequest request) {
        if (request == null || request.problemIds() == null || request.problemIds().size() != 1) {
            return ErrorResponses.failure(ProblemErrorCode.PROBLEM_INPUT_INVALID, "预览一次只能选择一道题目");
        }
        return Result.success(appService.previewFormatProblem(request.problemIds().get(0), request.ipEnabled()));
    }

    @Operation(summary = "应用单题整理结果（支持字段白名单与原文哈希校验）")
    @AuditLog(module = "problem", action = "apply_format")
    @PostMapping("/format/apply")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<ProblemFormatApplyResult> applyFormattedProblem(@RequestBody ProblemFormatApplyRequest request) {
        return Result.success(appService.applyFormattedProblem(request));
    }

    @Operation(summary = "保存标准答案（AI 题库工具落库）")
    @AuditLog(module = "problem", action = "save_std")
    @PostMapping("/std/save")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<String> saveStd(@RequestBody Map<String, String> body) {
        if (body == null || body.get("problemId") == null || body.get("problemId").trim().isEmpty()) {
            return ErrorResponses.failure(ProblemErrorCode.PROBLEM_INPUT_INVALID, "题目 ID 不能为空");
        }
        appService.saveStd(RequestValues.longValue(body.get("problemId"), "problemId"), body.get("code"), body.get("explanation"));
        return Result.success("标准答案已保存");
    }

    @Operation(summary = "读取标准答案（仅教师/管理员，学生不可见）")
    @GetMapping("/{id}/std")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<Map<String, Object>> getStd(@PathVariable Long id) {
        return Result.success(appService.getStd(id));
    }

    @Operation(summary = "清除标准答案（AI 题库工具，置空代码与思路）")
    @AuditLog(module = "problem", action = "clear_std")
    @PostMapping("/std/clear")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<String> clearStd(@RequestBody Map<String, Long> body) {
        if (body == null || body.get("problemId") == null) {
            return ErrorResponses.failure(ProblemErrorCode.PROBLEM_INPUT_INVALID, "题目 ID 不能为空");
        }
        appService.clearStd(body.get("problemId"));
        return Result.success("标准答案已清除");
    }

    /** 测试并保存请求体 */
    public record TestAndSaveStdRequest(Long problemId, String code, String explanation) {}

    /**
     * 标准答案「测试并保存」：先用现有测试数据逐组验证新 std，
     * 全部通过（AC）才保存；无用例、有失败或验证期间用例被改动都不保存。
     */
    @Operation(summary = "标准答案测试并保存（现有测试数据全部通过才保存）")
    @AuditLog(module = "problem", action = "test_save_std")
    @PostMapping("/std/test-and-save")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<Map<String, Object>> testAndSaveStd(@RequestBody TestAndSaveStdRequest request) {
        if (request == null || request.problemId() == null) {
            return ErrorResponses.failure(ProblemErrorCode.PROBLEM_INPUT_INVALID, "题目 ID 不能为空");
        }
        if (request.code() == null || request.code().isBlank()) {
            return ErrorResponses.failure(ProblemErrorCode.PROBLEM_INPUT_INVALID, "标准答案代码不能为空");
        }
        TestDataGenerationService.StdVerifyResult verify =
                testDataGenerationService.verifyStdAgainstCases(request.problemId(), request.code());
        Map<String, Object> result = new HashMap<>();
        result.put("total", verify.total());
        result.put("passed", verify.passed());
        result.put("failed", verify.failed());
        result.put("failures", verify.failures());

        if (verify.failed() != 0) {
            result.put("saved", false);
            result.put("message", "测试未通过：" + verify.passed() + "/" + verify.total() + " 组通过，未保存");
            return Result.success(result);
        }

        // 验证耗时可达 1~2 分钟，期间用例可能被另一个窗口改掉。
        // 一致性校验在 saveStdTested 内部完成：取题目写锁 → 重算指纹 → 比对 → 写库，
        // 与用例写入口用同一把锁串行化；此处不再单独比对（那两段式检查之间仍有窗口）。
        try {
            appService.saveStdTested(request.problemId(), request.code(), request.explanation(),
                    verify.caseFingerprint());
        } catch (CaseSetChangedException e) {
            result.put("saved", false);
            result.put("message", e.getMessage());
            return Result.success(result);
        }
        result.put("saved", true);
        result.put("message", "标准答案已通过全部 " + verify.total() + " 组测试数据并保存");
        return Result.success(result);
    }

    /** 清空用例并保存请求体 */
    public record SaveStdAndClearCasesRequest(Long problemId, String code, String explanation) {}

    /**
     * 标准答案「清空测试用例并保存」：保存新 std 的同时清空该题全部测试用例
     * （修复「原 std 错误 → 测试数据也错误」的场景，之后需重新生成测试数据）。
     */
    @Operation(summary = "标准答案保存并清空全部测试用例")
    @AuditLog(module = "problem", action = "save_std_clear_cases")
    @PostMapping("/std/save-and-clear-cases")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<String> saveStdAndClearCases(@RequestBody SaveStdAndClearCasesRequest request) {
        if (request == null || request.problemId() == null) {
            return ErrorResponses.failure(ProblemErrorCode.PROBLEM_INPUT_INVALID, "题目 ID 不能为空");
        }
        appService.saveStdAndClearCases(request.problemId(), request.code(), request.explanation());
        return Result.success("标准答案已保存，该题测试数据已全部清空，请重新生成测试数据");
    }

    @Operation(summary = "题目详情")
    @GetMapping("/{id}")
    public Result<ProblemDetailVO> getProblemDetail(@PathVariable Long id) {
        return Result.success(appService.getDetail(id));

    }

    @Operation(summary = "更新题目")
    @AuditLog(module = "problem", action = "update_problem")
    @PostMapping("/update")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<String> updateProblem(@RequestBody ProblemDTO dto) {
        appService.update(dto);
        return Result.success("更新成功");
    }

    @Operation(summary = "删除题目")
    @AuditLog(module = "problem", action = "delete_problem")
    @PostMapping("/delete/{id}")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<String> deleteProblem(@PathVariable Long id) {
        appService.delete(id);
        return Result.success("删除成功");
    }

    @Operation(summary = "获取测试用例（含输入/输出内容，超长截断）")
    @GetMapping("/cases/{problemId}")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<List<ProblemCaseVO>> getProblemCases(@PathVariable Long problemId) {
        return Result.success(appService.getPrivateCases(problemId));
    }

    @Operation(summary = "上传测试用例")
    @AuditLog(module = "problem", action = "upload_cases")
    @PostMapping("/upload-cases")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public Result<Integer> uploadCases(@RequestParam("file") MultipartFile file,
                                       @RequestParam("problemId") Long problemId) {
        if (file.isEmpty()) return ErrorResponses.failure(ProblemErrorCode.PROBLEM_INPUT_INVALID, "文件为空");
        return Result.success(appService.importCases(problemId, file));
    }

    @Operation(summary = "导入 FPS 题库")
    @AuditLog(module = "problem", action = "import_fps")
    @PostMapping("/import/fps")
    @PreAuthorize("hasAuthority('problem:manage')")
    public Result<FpsImportResult> importFps(@RequestParam("file") MultipartFile file) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return ErrorResponses.failure(CommonErrorCode.AUTH_REQUIRED, "请先登录");
        try {
            return Result.success(fpsImportService.importFile(file, userId));
        } catch (IllegalArgumentException e) {
            return ErrorResponses.failure(ProblemErrorCode.PROBLEM_INPUT_INVALID, e.getMessage());
        }
    }
}
