package top.everblue.oj.judge.worker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.judge.JudgeGateway;
import top.everblue.oj.api.problem.ProblemQuery;
import top.everblue.oj.api.problem.TestCaseView;
import top.everblue.oj.judge.domain.SubmissionStatus;
import top.everblue.oj.judge.entity.Submission;
import top.everblue.oj.judge.entity.SubmissionCode;
import top.everblue.oj.judge.entity.SubmissionResult;
import top.everblue.oj.judge.mapper.SubmissionCodeMapper;
import top.everblue.oj.judge.mapper.SubmissionMapper;
import top.everblue.oj.judge.mapper.SubmissionResultMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 判题编排器 —— 判题链路的唯一编排逻辑。
 * <p>
 * 从 {@link JudgeWorker} 中抽取（评审 O4）：同步/异步只是触发方式不同，
 * 「加载提交 → 用例并行调 Judge0 → 回写提交 → 可选落库标准化结果」必须只有一份。
 * 定时轮询 Worker 与未来的 judge-worker 进程都通过本编排器执行判题。
 * </p>
 *
 * <h3>用例并行（高并发方案 A3-1）</h3>
 * 用例通过专用线程池 {@code judgeCaseExecutor} 并行执行（并发上限 = case-parallelism，默认 4，
 * 按 Judge0 槽位配置）。语义保持：
 * <ul>
 *   <li>subtask 组聚合不变：组内任一用例失败 → 整组 0 分（组内已累加分数作废）</li>
 *   <li>组失败跳过保留（尽力而为）：用例提交前检查组失败标记，已失败的组不再发起 Judge0 调用</li>
 *   <li>最终状态取"最早 index 的失败用例"，AC 时分数 = 各组 AC 分数之和</li>
 * </ul>
 * 与原串行实现的差异：失败用例后续的同组用例可能已并行发出（最多浪费一批并行额度），
 * 换来的是整个提交的判题墙钟时间从 用例数 × 单用例耗时 降到 ≈ 波次 × 单用例耗时。
 */
@Slf4j
@Service
public class JudgeOrchestrator {

    private final JudgeGateway judgeGateway;
    private final SubmissionMapper submissionMapper;
    private final SubmissionCodeMapper submissionCodeMapper;
    private final ProblemQuery problemQuery;
    private final SubmissionResultMapper submissionResultMapper;
    private final Executor caseExecutor;

    @Value("${oj.judge.persist-normalized-results:false}")
    private boolean persistNormalizedResults;

    public JudgeOrchestrator(JudgeGateway judgeGateway,
                             SubmissionMapper submissionMapper,
                             SubmissionCodeMapper submissionCodeMapper,
                             ProblemQuery problemQuery,
                             SubmissionResultMapper submissionResultMapper,
                             @Qualifier("judgeCaseExecutor") Executor caseExecutor) {
        this.judgeGateway = judgeGateway;
        this.submissionMapper = submissionMapper;
        this.submissionCodeMapper = submissionCodeMapper;
        this.problemQuery = problemQuery;
        this.submissionResultMapper = submissionResultMapper;
        this.caseExecutor = caseExecutor;
    }

    /**
     * 执行一次完整判题，返回最终状态。
     *
     * @param submissionId 待判提交 ID
     * @return 最终 {@link SubmissionStatus}（ACCEPTED 或首个失败用例状态）
     * @throws IllegalStateException 提交不存在或测试用例为空（由调用方走 fail 流程）
     */
    public SubmissionStatus executeJudge(Long submissionId) {
        // 1) 加载数据
        Submission submission = submissionMapper.selectById(submissionId);
        if (submission == null) {
            throw new IllegalStateException("submission 不存在: " + submissionId);
        }
        loadCode(submission); // V50 拆表：源码从 submission_code 补载（判题需要）
        submission.setJudgePhase("COMPILING");
        submissionMapper.updateById(submission);

        int[] limits = problemQuery.findProblemLimits(submission.getProblemId());

        List<TestCaseView> testCases = problemQuery.findTestCases(submission.getProblemId());

        if (testCases.isEmpty()) {
            throw new IllegalStateException("测试用例为空 problemId=" + submission.getProblemId());
        }

        // 2) 准备 Judge0 参数
        double timeLimitSec = Math.max(limits[0], 1000) / 1000.0;
        int memoryLimitKb = Math.max(limits[1], 131072);

        // 3) 用例并行判题，按 subtask 组聚合（ADR-0003）：
        //    组内全部 AC 才得组总分（组内 score 之和），否则整组 0 分；
        //    组已失败后跳过剩余同组用例（尽力而为：提交前检查失败标记）。
        //    并发安全：聚合容器全部使用并发集合/原子量，submission 的最终回写只在主线程。
        ConcurrentHashMap<Integer, Integer> groupScores = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, Boolean> groupFailed = new ConcurrentHashMap<>();
        AtomicInteger maxTimeMs = new AtomicInteger(0);
        AtomicInteger maxMemoryKb = new AtomicInteger(0);
        AtomicBoolean phaseUpdated = new AtomicBoolean(false);
        AtomicInteger firstFailureIndex = new AtomicInteger(Integer.MAX_VALUE);
        AtomicReference<SubmissionStatus> firstFailureStatus = new AtomicReference<>();
        AtomicReference<Map<String, Object>> firstFailureResult = new AtomicReference<>();

        List<CompletableFuture<Void>> futures = new ArrayList<>(testCases.size());
        for (int index = 0; index < testCases.size(); index++) {
            TestCaseView oneCase = testCases.get(index);
            int subtaskNo = oneCase.subtaskNo() != null ? oneCase.subtaskNo() : 1;
            final int idx = index;

            futures.add(CompletableFuture.runAsync(() -> {
                if (Boolean.TRUE.equals(groupFailed.get(subtaskNo))) {
                    return; // 该子任务已有失败用例，整组 0 分，无需再判（尽力而为跳过）
                }

                Map<String, Object> result = judgeGateway.judge(
                        submission.getCode(),
                        submission.getLanguage(),
                        oneCase.input(),
                        oneCase.output(),
                        timeLimitSec,
                        memoryLimitKb
                );

                // 任一用例完成（非 CE）即进入 JUDGING 阶段（原串行实现以首个用例为准）
                if (phaseUpdated.compareAndSet(false, true)) {
                    submission.setJudgePhase("JUDGING");
                    submissionMapper.updateById(submission);
                }

                int judge0Status = numberValue(result.get("status"));
                int timeMs = (int) (decimalValue(result.get("time")) * 1000);
                int memoryKb = numberValue(result.get("memory"));

                maxTimeMs.accumulateAndGet(timeMs, Math::max);
                maxMemoryKb.accumulateAndGet(memoryKb, Math::max);

                SubmissionStatus caseStatus = SubmissionStatus.fromJudge0(judge0Status);

                if (caseStatus != SubmissionStatus.ACCEPTED) {
                    groupFailed.put(subtaskNo, true);
                    groupScores.remove(subtaskNo); // 组已失败：之前累加的同组分数作废，整组 0 分
                    synchronized (firstFailureIndex) {
                        if (idx < firstFailureIndex.get()) {
                            firstFailureIndex.set(idx);
                            firstFailureStatus.set(caseStatus);
                            firstFailureResult.set(result);
                        }
                    }
                    return;
                }

                groupScores.merge(subtaskNo, oneCase.score() != null ? oneCase.score() : 0, Integer::sum);
            }, caseExecutor));
        }

        // 等待全部用例完成（单个用例超时由 JudgeGateway 侧 HTTP 超时兜底，
        // 极端超时由 JudgeTaskWatchdog 按任务级别回收）
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 4) 聚合与回写
        SubmissionStatus finalStatus = firstFailureStatus.get() != null
                ? firstFailureStatus.get() : SubmissionStatus.ACCEPTED;

        int subtaskTotal = groupScores.values().stream().mapToInt(Integer::intValue).sum();
        submission.setStatus(finalStatus.code());
        submission.setJudgePhase("FINISHED");
        submission.setRunTime(maxTimeMs.get());
        submission.setRunMemory(maxMemoryKb.get());
        submission.setScore(finalStatus == SubmissionStatus.ACCEPTED
                ? (subtaskTotal == 0 ? 100 : subtaskTotal)
                : subtaskTotal);

        Map<String, Object> failureResult = firstFailureResult.get();
        if (failureResult != null && finalStatus == SubmissionStatus.COMPILATION_ERROR) {
            submission.setErrorMessage((String) failureResult.get("compile_output"));
        } else if (failureResult != null && finalStatus == SubmissionStatus.RUNTIME_ERROR) {
            String errMsg = (String) failureResult.get("message");
            if (errMsg == null) errMsg = (String) failureResult.get("stderr");
            submission.setErrorMessage(errMsg);
        }

        submissionMapper.updateById(submission);

        if (persistNormalizedResults) {
            persistNormalizedResult(submission);
        }

        log.info("【判题】完成 submissionId={}, status={}, time={}ms, memory={}kb, cases={}",
                submissionId, finalStatus.code(), maxTimeMs.get(), maxMemoryKb.get(), testCases.size());
        return finalStatus;
    }

    private void persistNormalizedResult(Submission submission) {
        SubmissionResult result = new SubmissionResult();
        result.setSubmissionId(submission.getId());
        result.setStatus(submission.getStatus());
        result.setScore(submission.getScore());
        result.setRunTime(submission.getRunTime());
        result.setRunMemory(submission.getRunMemory());
        result.setErrorMessage(submission.getErrorMessage());
        result.setJudgedAt(LocalDateTime.now());
        submissionResultMapper.insert(result);
    }

    // ---- 数值转换辅助方法（Judge0 返回 JSON 中 status/time/memory 可能是数字或字符串） ----

    private int numberValue(Object obj) {
        if (obj instanceof Number n) return n.intValue();
        if (obj instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private double decimalValue(Object obj) {
        if (obj instanceof Number n) return n.doubleValue();
        if (obj instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0.0;
    }

    /**
     * V50 拆表后补载提交源码（submission 主表不再含 code）。
     */
    private void loadCode(Submission submission) {
        if (submission.getId() == null) {
            return;
        }
        SubmissionCode codeRecord = submissionCodeMapper.selectById(submission.getId());
        submission.setCode(codeRecord == null ? null : codeRecord.getCode());
    }
}
