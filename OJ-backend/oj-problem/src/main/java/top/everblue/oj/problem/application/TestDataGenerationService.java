package top.everblue.oj.problem.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.judge.JudgeGateway;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.problem.entity.Problem;
import top.everblue.oj.problem.entity.ProblemCase;
import top.everblue.oj.problem.service.ProblemCaseService;
import top.everblue.oj.problem.service.ProblemService;
import top.everblue.oj.problem.service.TestCaseStorageService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 测试数据生成与验证（生成器模式，AI 题库工具用）。
 * <p>
 * 流程：在 Judge0 沙箱执行 Python 生成器 → 按 {@link #CASE_SEPARATOR} 切分多组输入 →
 * 逐组用题目已保存的 std 实测输出（编译通过且正常产出 stdout 才算通过）→ 统计通过/失败。
 * 失败组不落库；通过的组由前端确认后调用追加保存接口入库。
 * </p>
 * <p>
 * 安全：生成器与 std 均在 Judge0 沙箱执行，不在应用服务器本机运行。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestDataGenerationService {

    /** 生成器输出中用于切分多组测试数据的分隔行（与 AI 提示词约定一致） */
    public static final String CASE_SEPARATOR = "===CASE===";

    /** 生成器单次输出保护上限（字符数；Judge0 stdout 默认约 256KB，留余量） */
    private static final int MAX_GENERATOR_OUTPUT_CHARS = 200 * 1024;

    /** 生成器运行超时（秒），兜住死循环/低效代码 */
    private static final double GENERATOR_TIME_LIMIT_SEC = 10.0;

    /** 生成器内存限制（KB） */
    private static final int GENERATOR_MEMORY_LIMIT_KB = 262144;

    /** std 逐组验证并发度（Judge0 槽位有限，保守取值） */
    private static final int VERIFY_PARALLELISM = 4;

    /** 失败原因详情截断长度 */
    private static final int REASON_MAX_LEN = 120;

    private static final ExecutorService VERIFY_EXECUTOR = Executors.newFixedThreadPool(VERIFY_PARALLELISM, r -> {
        Thread t = new Thread(r, "testdata-verify");
        t.setDaemon(true);
        return t;
    });

    private final ProblemService problemService;
    private final ProblemCaseService problemCaseService;
    private final TestCaseStorageService testCaseStorageService;
    private final JudgeGateway judgeGateway;

    /** 通过 std 实测的候选用例（尚未入库） */
    public record DraftCase(String input, String output, String note) {}

    /** 生成 + 验证统计结果 */
    public record GenerateVerifyResult(int total, int passed, int failed,
                                       List<DraftCase> cases, List<String> failedReasons) {}

    /** std 对现有测试数据的逐组验证结果（只验证不落库） */
    public record StdVerifyResult(int total, int passed, int failed, List<StdFailure> failures) {}

    /** 单组失败明细：第几个用例 + 失败原因 */
    public record StdFailure(int caseOrder, String reason) {}

    /**
     * 执行生成器并逐组用 std 实测。
     *
     * @throws IllegalArgumentException 无 std / 生成器代码为空 / 生成器运行失败 / 输出超限或不可解析
     */
    public GenerateVerifyResult generateAndVerify(Long problemId, String generatorCode) {
        if (problemId == null) {
            throw new IllegalArgumentException("题目ID不能为空");
        }
        SecurityUtils.requireTeacherOrAdmin();
        Problem problem = problemService.getById(problemId);
        if (problem == null) {
            throw new IllegalArgumentException("题目不存在");
        }
        SecurityUtils.requireOwnerOrAdmin(problem.getAuthorId());

        String stdCode = problem.getStdAnswer();
        if (stdCode == null || stdCode.isBlank()) {
            throw new IllegalArgumentException("该题尚未保存标准答案，请先在「标准答案」中生成并保存，再生成测试数据");
        }
        if (generatorCode == null || generatorCode.isBlank()) {
            throw new IllegalArgumentException("生成器代码不能为空");
        }

        // 1. 执行生成器（Judge0 沙箱，无输入，只读 stdout）
        Map<String, Object> genResult = judgeGateway.judge(
                generatorCode, "python", "", null,
                GENERATOR_TIME_LIMIT_SEC, GENERATOR_MEMORY_LIMIT_KB);
        int genStatus = statusOf(genResult);
        String genStdout = stringOf(genResult.get("stdout"));
        if (genStatus != 3) {
            throw new IllegalArgumentException("生成器运行失败：" + describeFailure(genStatus, genResult));
        }
        if (genStdout == null || genStdout.isBlank()) {
            throw new IllegalArgumentException("生成器没有任何输出，请修改生成器后重新执行");
        }
        if (genStdout.length() > MAX_GENERATOR_OUTPUT_CHARS) {
            throw new IllegalArgumentException("生成器输出超过 200KB 保护上限，请减小数据规模后重新执行");
        }

        // 2. 按分隔符切分为多组输入，过滤空组
        List<String> inputs = splitCases(genStdout).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("生成器输出无法解析出测试数据（未找到 " + CASE_SEPARATOR + " 分隔符），请修改生成器后重试");
        }

        // 3. 逐组用 std 实测（并发，结果按组序归位）
        Double timeLimitSec = problem.getTimeLimit() != null ? problem.getTimeLimit() / 1000.0 : 1.0;
        Integer memoryLimitKb = problem.getMemoryLimit() != null ? problem.getMemoryLimit() * 1024 : 262144;

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        ConcurrentLinkedQueue<Object[]> results = new ConcurrentLinkedQueue<>(); // [idx, ok, outputOrReason]
        for (int i = 0; i < inputs.size(); i++) {
            final int idx = i;
            final String input = inputs.get(i);
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    Map<String, Object> r = judgeGateway.judge(
                            stdCode, "cpp", input, null, timeLimitSec, memoryLimitKb);
                    int st = statusOf(r);
                    String out = stringOf(r.get("stdout"));
                    if (st == 3 && out != null && !out.isBlank()) {
                        results.add(new Object[]{idx, true, out});
                    } else {
                        results.add(new Object[]{idx, false, describeFailure(st, r)});
                    }
                } catch (Exception e) {
                    log.warn("std 实测异常 problemId={} case#{}: {}", problemId, idx, e.getMessage());
                    results.add(new Object[]{idx, false, "判题服务异常，请稍后重试"});
                }
            }, VERIFY_EXECUTOR));
        }
        for (CompletableFuture<Void> f : futures) {
            try {
                f.join();
            } catch (Exception ignored) {
                // 单个用例异常已在上方捕获，join 异常仅兜底
            }
        }

        Object[][] ordered = results.toArray(new Object[0][]);
        Arrays.sort(ordered, Comparator.comparingInt(a -> (int) a[0]));

        List<DraftCase> cases = new ArrayList<>();
        List<String> failedReasons = new ArrayList<>();
        int passed = 0;
        int failed = 0;
        for (Object[] row : ordered) {
            int idx = (int) row[0];
            boolean ok = (boolean) row[1];
            if (ok) {
                passed++;
                cases.add(new DraftCase(inputs.get(idx), ((String) row[2]).replaceAll("\\s+$", ""),
                        "std 实测通过"));
            } else {
                failed++;
                failedReasons.add("第 " + (idx + 1) + " 组：" + row[2]);
            }
        }
        log.info("测试数据生成验证完成 problemId={} total={} passed={} failed={}", problemId, inputs.size(), passed, failed);
        return new GenerateVerifyResult(inputs.size(), passed, failed, cases, failedReasons);
    }

    /**
     * 用该题现有测试数据逐组验证 std（expectedOutput 传入，Judge0 自动比对输出）。
     * <p>
     * 供「测试并保存」使用：全部 AC 才允许保存；只验证不落库。
     * </p>
     *
     * @throws IllegalArgumentException 题目不存在 / std 为空
     */
    public StdVerifyResult verifyStdAgainstCases(Long problemId, String stdCode) {
        if (problemId == null) {
            throw new IllegalArgumentException("题目ID不能为空");
        }
        SecurityUtils.requireTeacherOrAdmin();
        Problem problem = problemService.getById(problemId);
        if (problem == null) {
            throw new IllegalArgumentException("题目不存在");
        }
        SecurityUtils.requireOwnerOrAdmin(problem.getAuthorId());
        if (stdCode == null || stdCode.isBlank()) {
            throw new IllegalArgumentException("标准答案代码不能为空");
        }

        List<ProblemCase> cases = problemCaseService.getProblemCases(problemId);
        if (cases.isEmpty()) {
            return new StdVerifyResult(0, 0, 0, List.of());
        }
        Double timeLimitSec = problem.getTimeLimit() != null ? problem.getTimeLimit() / 1000.0 : 1.0;
        Integer memoryLimitKb = problem.getMemoryLimit() != null ? problem.getMemoryLimit() * 1024 : 262144;

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        ConcurrentLinkedQueue<Object[]> results = new ConcurrentLinkedQueue<>(); // [idx, ok, reason]
        for (int i = 0; i < cases.size(); i++) {
            final ProblemCase pc = cases.get(i);
            final int idx = i;
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    String input = testCaseStorageService.readByRelativePath(pc.getInputFilePath());
                    String expected = testCaseStorageService.readByRelativePath(pc.getOutputFilePath());
                    Map<String, Object> r = judgeGateway.judge(
                            stdCode, "cpp", input == null ? "" : input, expected,
                            timeLimitSec, memoryLimitKb);
                    int st = statusOf(r);
                    results.add(new Object[]{idx, st == 3, describeFailure(st, r)});
                } catch (Exception e) {
                    log.warn("std 验证异常 caseId={}: {}", pc.getId(), e.getMessage());
                    results.add(new Object[]{idx, false, "判题服务异常，请稍后重试"});
                }
            }, VERIFY_EXECUTOR));
        }
        for (CompletableFuture<Void> f : futures) {
            try {
                f.join();
            } catch (Exception ignored) {
                // 单组异常已在上方捕获
            }
        }

        Object[][] ordered = results.toArray(new Object[0][]);
        Arrays.sort(ordered, Comparator.comparingInt(a -> (int) a[0]));
        int passed = 0;
        List<StdFailure> failures = new ArrayList<>();
        for (Object[] row : ordered) {
            int idx = (int) row[0];
            boolean ok = (boolean) row[1];
            if (ok) {
                passed++;
            } else {
                Integer caseOrder = cases.get(idx).getCaseOrder();
                failures.add(new StdFailure(caseOrder == null ? idx + 1 : caseOrder, (String) row[2]));
            }
        }
        log.info("std 现有用例验证完成 problemId={} total={} passed={} failed={}", problemId, cases.size(), passed, cases.size() - passed);
        return new StdVerifyResult(cases.size(), passed, cases.size() - passed, failures);
    }

    /** 按 {@link #CASE_SEPARATOR} 切分多组数据；无分隔符时整段视为一组。 */
    static List<String> splitCases(String stdout) {
        List<String> groups = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : stdout.split("\n", -1)) {
            if (line.trim().equals(CASE_SEPARATOR)) {
                if (cur.length() > 0) {
                    groups.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(line).append('\n');
            }
        }
        if (cur.length() > 0) {
            groups.add(cur.toString());
        }
        return groups;
    }

    /** 将 Judge0 状态码转换为可读失败原因（status=3 时表示无输出）。 */
    static String describeFailure(int status, Map<String, Object> result) {
        if (status == 3) {
            return "无输出";
        }
        String detail = firstNonBlank(stringOf(result.get("stderr")),
                stringOf(result.get("compile_output")),
                stringOf(result.get("message")));
        String brief = detail.isBlank() ? "" : "：" + (detail.length() > REASON_MAX_LEN
                ? detail.substring(0, REASON_MAX_LEN) + "…" : detail);
        return switch (status) {
            case 5 -> "运行超时";
            case 6 -> "编译失败" + brief;
            case 7 -> "内存超限";
            case 8, 9, 10, 11, 12, 13 -> "运行报错" + brief;
            default -> "运行失败（状态 " + status + "）";
        };
    }

    static int statusOf(Map<String, Object> result) {
        Object v = result == null ? null : result.get("status");
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(String.valueOf(v));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return 0;
    }

    static String stringOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }
}
