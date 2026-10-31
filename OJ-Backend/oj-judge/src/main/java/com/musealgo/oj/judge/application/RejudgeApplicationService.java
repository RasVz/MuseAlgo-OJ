package com.musealgo.oj.judge.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.musealgo.oj.api.contest.ContestSchedule;
import com.musealgo.oj.api.contest.ContestScheduleQuery;
import com.musealgo.oj.api.problem.ProblemQuery;
import com.musealgo.oj.api.problem.TestCaseView;
import com.musealgo.oj.judge.dto.RejudgeBatchScope;
import com.musealgo.oj.judge.dto.RejudgeOperationVO;
import com.musealgo.oj.judge.dto.RejudgePreviewVO;
import com.musealgo.oj.judge.entity.JudgeTask;
import com.musealgo.oj.judge.entity.RejudgeItem;
import com.musealgo.oj.judge.entity.RejudgeOperation;
import com.musealgo.oj.judge.entity.Submission;
import com.musealgo.oj.judge.mapper.JudgeTaskMapper;
import com.musealgo.oj.judge.mapper.RejudgeItemMapper;
import com.musealgo.oj.judge.mapper.RejudgeOperationMapper;
import com.musealgo.oj.judge.mapper.SubmissionMapper;
import com.musealgo.oj.judge.messaging.JudgeTaskNotifier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 受控重判应用服务（T8-1 单提交 + T8-2a 范围化批量）。
 * <p>
 * 职责：准入校验（A 冻结历史）→ 保存旧结果快照 → 写操作/条目 → 把提交与判题任务重置为待判。
 * 判题完成后由 {@code JudgeCompletionService} 回写条目并发布派生数据重建事件；
 * 失败重试耗尽时由 {@code JudgeTaskServiceImpl} 恢复旧结果。
 * </p>
 * <p>
 * 批量（T8-2a）只做范围选择/dry-run/上限/进度，不重算历史 Rating；VP 提交重判需要同步重算 VP 成绩，
 * 规划在 T8-2b，本批与单提交一样拒绝。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RejudgeApplicationService {

    /** 判题中/待判阶段禁止再重判，避免与在途 Worker 互相覆盖。 */
    private static final Set<String> BUSY_JUDGE_PHASES = Set.of("QUEUED", "COMPILING", "JUDGING");
    /** 单次批量上限：避免一次请求长时间占用判题队列/事务。 */
    public static final int MAX_BATCH_ITEMS = 200;
    /** 预览/执行扫描上限：超过则要求缩小范围，防止一次拉回全表。 */
    static final int MAX_SCAN = 10_000;

    private static final String SCOPE_PROBLEM = "PROBLEM";
    private static final String SCOPE_FAILED = "FAILED";
    private static final String SCOPE_ENDED_CONTEST = "ENDED_CONTEST";
    private static final String SCOPE_TIME_RANGE = "TIME_RANGE";

    // 比赛准入状态（缓存值，避免同一批内重复查库）
    private static final int CONTEST_ALLOWED = 1;
    private static final int CONTEST_FINALIZED = 2;
    private static final int CONTEST_RUNNING = 3;
    private static final int CONTEST_MISSING = 4;

    private final SubmissionMapper submissionMapper;
    private final JudgeTaskMapper judgeTaskMapper;
    private final RejudgeOperationMapper operationMapper;
    private final RejudgeItemMapper itemMapper;
    private final ContestScheduleQuery contestScheduleQuery;
    private final ProblemQuery problemQuery;
    private final ObjectProvider<JudgeTaskNotifier> judgeTaskNotifier;

    // ==================================================================
    // T8-1 单提交
    // ==================================================================

    /**
     * 发起单提交重判。
     *
     * @param operatorId   发起人（管理员）
     * @param operatorName 发起人展示名（审计冗余，可为 null）
     */
    @Transactional(rollbackFor = Exception.class)
    public RejudgeOperationVO rejudgeSingleSubmission(Long submissionId, String reason,
                                                      Long operatorId, String operatorName) {
        validateReason(reason);
        if (submissionId == null) {
            throw new IllegalArgumentException("提交 ID 不能为空");
        }

        // 幂等：同一提交已有未结束重判 → 返回既有操作，不重复入队。
        RejudgeItem openItem = itemMapper.selectOpenBySubmissionId(submissionId);
        if (openItem != null) {
            return loadOperation(openItem.getOperationId());
        }

        // 行锁住提交，确保“校验 + 重置”之间不会被判题完成写回插入。
        Submission submission = submissionMapper.selectOne(new LambdaQueryWrapper<Submission>()
                .eq(Submission::getId, submissionId)
                .last("FOR UPDATE"));
        if (submission == null) {
            throw new IllegalArgumentException("提交不存在");
        }
        if (submission.getStatus() != null && submission.getStatus() == 0) {
            throw new IllegalStateException("提交正在判题中，不能重判");
        }
        if (submission.getJudgePhase() != null && BUSY_JUDGE_PHASES.contains(submission.getJudgePhase())) {
            throw new IllegalStateException("提交正在判题中（" + submission.getJudgePhase() + "），不能重判");
        }
        if (submission.getVpAttemptId() != null) {
            // T8-2b：VP 不影响正式榜/Rating，只要求会话已交卷冻结；重判完成后由 contest 监听器重算 VP 成绩。
            if (!contestScheduleQuery.isVpAttemptFinished(submission.getVpAttemptId())) {
                throw new IllegalStateException("VP 会话尚未结束（RUNNING/SETTLING），不能重判");
            }
        } else {
            String contestBlock = checkContest(submission.getContestId());
            if (contestBlock != null) {
                throw new IllegalStateException(contestBlock);
            }
        }

        List<TestCaseView> testCases = problemQuery.findTestCases(submission.getProblemId());
        if (testCases == null || testCases.isEmpty()) {
            throw new IllegalStateException("题目没有可用测试用例，无法重判");
        }
        String digest = testCaseDigest(testCases);
        // DATETIME(0) 会四舍五入纳秒：截断到秒，避免 scheduled_at 落到未来导致领取不到任务。
        LocalDateTime now = LocalDateTime.now().withNano(0);

        RejudgeOperation operation = newOperation(operatorId, operatorName, reason,
                RejudgeOperation.SCOPE_SINGLE_SUBMISSION, "{\"submissionId\":" + submissionId + "}",
                1, now);
        operationMapper.insert(operation);

        RejudgeItem item = newItem(operation.getId(), submission, digest, now);
        itemMapper.insert(item);

        resetSubmission(submission.getId(), now);
        resetJudgeTask(submission.getId(), now);

        // MQ 唤醒失败不影响正确性：DB 轮询是兜底路径（与提交链路同一策略）。
        judgeTaskNotifier.ifAvailable(notifier -> notifier.notifyNewTask(submission.getId()));
        log.info("【T8 重判】已受理单提交 submissionId={}, operationId={}, operatorId={}, digest={}",
                submissionId, operation.getId(), operatorId, digest);

        return loadOperation(operation.getId());
    }

    // ==================================================================
    // T8-2a 范围化批量
    // ==================================================================

    /** dry-run 预览：只读统计，不产生任何写入。 */
    public RejudgePreviewVO previewBatch(RejudgeBatchScope scope) {
        BatchEvaluation evaluation = evaluate(scope);
        return new RejudgePreviewVO(
                evaluation.scopeType(),
                evaluation.matched(),
                evaluation.eligible().size(),
                evaluation.blockedFinalized(),
                evaluation.blockedRunning(),
                evaluation.blockedVp(),
                evaluation.blockedPending(),
                evaluation.blockedNoTestCases(),
                evaluation.blockedInFlight(),
                evaluation.blockedMissingContest(),
                MAX_BATCH_ITEMS,
                evaluation.eligible().size() > MAX_BATCH_ITEMS,
                evaluation.truncated(),
                evaluation.eligible().stream().limit(10).map(Submission::getId).toList());
    }

    /**
     * 执行批量重判：同一事务内创建 operation/items 并重置所有提交与任务。
     * <p>不逐条发 MQ 唤醒：DB 轮询会在下个周期批量领取，避免一次触发上百条消息。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public RejudgeOperationVO rejudgeBatch(RejudgeBatchScope scope, String reason,
                                          Long operatorId, String operatorName) {
        validateReason(reason);
        BatchEvaluation evaluation = evaluate(scope);
        if (evaluation.matched() == 0) {
            throw new IllegalArgumentException("该范围没有匹配的提交");
        }
        if (evaluation.truncated()) {
            throw new IllegalStateException("匹配提交超过扫描上限 " + MAX_SCAN + "，请缩小范围");
        }
        if (evaluation.eligible().isEmpty()) {
            throw new IllegalStateException("没有可重判的提交：" + evaluation.blockedSummary());
        }
        if (evaluation.eligible().size() > MAX_BATCH_ITEMS) {
            throw new IllegalStateException("可重判提交 " + evaluation.eligible().size()
                    + " 条，超过单批上限 " + MAX_BATCH_ITEMS + "，请缩小范围；可先用 preview 预览");
        }

        // DATETIME(0) 会四舍五入纳秒：截断到秒，避免 scheduled_at 落到未来导致领取不到任务。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        RejudgeOperation operation = newOperation(operatorId, operatorName, reason,
                "BATCH_" + evaluation.scopeType(), scopeJson(scope, evaluation.scopeType()),
                evaluation.eligible().size(), now);
        operationMapper.insert(operation);

        for (Submission submission : evaluation.eligible()) {
            String digest = evaluation.digestByProblem().get(submission.getProblemId());
            RejudgeItem item = newItem(operation.getId(), submission, digest, now);
            itemMapper.insert(item);
            resetSubmission(submission.getId(), now);
            resetJudgeTask(submission.getId(), now);
        }
        log.info("【T8 批量重判】已受理 operationId={}, scope={}, items={}, operatorId={}",
                operation.getId(), evaluation.scopeType(), evaluation.eligible().size(), operatorId);
        return loadOperation(operation.getId());
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /** 分页查询重判操作（不含条目明细）。 */
    public Page<RejudgeOperationVO> pageOperations(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(1, Math.min(size, 100));
        Page<RejudgeOperation> raw = operationMapper.selectPage(new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<RejudgeOperation>().orderByDesc(RejudgeOperation::getId));
        Page<RejudgeOperationVO> result = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        result.setRecords(raw.getRecords().stream().map(op -> toVO(op, null)).toList());
        return result;
    }

    /** 查询操作详情（含条目）。 */
    public RejudgeOperationVO getOperation(Long operationId) {
        return loadOperation(operationId);
    }

    // ==================================================================
    // 范围解析与分类
    // ==================================================================

    private BatchEvaluation evaluate(RejudgeBatchScope scope) {
        String type = normalizeScope(scope);
        List<Submission> candidates = selectCandidates(scope, type);
        boolean truncated = candidates.size() > MAX_SCAN;
        if (truncated) {
            candidates = new ArrayList<>(candidates.subList(0, MAX_SCAN));
        }

        int blockedFinalized = 0;
        int blockedRunning = 0;
        int blockedVp = 0;
        int blockedPending = 0;
        int blockedMissingContest = 0;
        Map<Long, Integer> contestCache = new HashMap<>();
        List<Submission> basicEligible = new ArrayList<>();

        for (Submission submission : candidates) {
            if (submission.getStatus() != null && submission.getStatus() == 0
                    || (submission.getJudgePhase() != null && BUSY_JUDGE_PHASES.contains(submission.getJudgePhase()))) {
                blockedPending++;
                continue;
            }
            if (submission.getVpAttemptId() != null) {
                // T8-2b：只有已 FINISHED 的 VP 允许重判；VP 不影响正式榜，无需比赛固化校验。
                if (contestScheduleQuery.isVpAttemptFinished(submission.getVpAttemptId())) {
                    basicEligible.add(submission);
                } else {
                    blockedVp++;
                }
                continue;
            }
            int contestState = contestState(submission.getContestId(), contestCache);
            if (contestState == CONTEST_FINALIZED) {
                blockedFinalized++;
                continue;
            }
            if (contestState == CONTEST_RUNNING) {
                blockedRunning++;
                continue;
            }
            if (contestState == CONTEST_MISSING) {
                blockedMissingContest++;
                continue;
            }
            basicEligible.add(submission);
        }

        // 在途重判（PENDING/RUNNING item）不重复入队；一次批量查询避免 N+1。
        Set<Long> openSubmissionIds = new HashSet<>();
        if (!basicEligible.isEmpty()) {
            List<Long> ids = basicEligible.stream().map(Submission::getId).toList();
            List<Long> openIds = itemMapper.selectOpenSubmissionIds(ids);
            if (openIds != null) {
                openSubmissionIds.addAll(openIds);
            }
        }
        List<Submission> withoutInFlight = new ArrayList<>();
        int blockedInFlight = 0;
        for (Submission submission : basicEligible) {
            if (openSubmissionIds.contains(submission.getId())) {
                blockedInFlight++;
            } else {
                withoutInFlight.add(submission);
            }
        }

        // 用例可用性 + 摘要按题目缓存。
        Map<Long, String> digestByProblem = new LinkedHashMap<>();
        Map<Long, Boolean> casesAvailable = new HashMap<>();
        List<Submission> eligible = new ArrayList<>();
        int blockedNoTestCases = 0;
        for (Submission submission : withoutInFlight) {
            Long problemId = submission.getProblemId();
            Boolean available = casesAvailable.get(problemId);
            if (available == null) {
                List<TestCaseView> cases = problemQuery.findTestCases(problemId);
                available = cases != null && !cases.isEmpty();
                casesAvailable.put(problemId, available);
                if (available) {
                    digestByProblem.put(problemId, testCaseDigest(cases));
                }
            }
            if (!available) {
                blockedNoTestCases++;
            } else {
                eligible.add(submission);
            }
        }

        return new BatchEvaluation(type, candidates.size(), eligible, digestByProblem,
                blockedFinalized, blockedRunning, blockedVp, blockedPending,
                blockedNoTestCases, blockedInFlight, blockedMissingContest, truncated);
    }

    private String normalizeScope(RejudgeBatchScope scope) {
        if (scope == null || scope.scopeType() == null) {
            throw new IllegalArgumentException("scopeType 不能为空");
        }
        String type = scope.scopeType().trim().toUpperCase();
        switch (type) {
            case SCOPE_PROBLEM -> {
                if (scope.problemId() == null) throw new IllegalArgumentException("PROBLEM 范围必须提供 problemId");
            }
            case SCOPE_ENDED_CONTEST -> {
                if (scope.contestId() == null) throw new IllegalArgumentException("ENDED_CONTEST 范围必须提供 contestId");
            }
            case SCOPE_TIME_RANGE -> {
                if (scope.from() == null || scope.to() == null) {
                    throw new IllegalArgumentException("TIME_RANGE 范围必须提供 from/to");
                }
                if (!scope.from().isBefore(scope.to())) {
                    throw new IllegalArgumentException("from 必须早于 to");
                }
            }
            case SCOPE_FAILED -> {
                // FAILED 可叠加 problemId/contestId/from/to 过滤，均为可选。
            }
            default -> throw new IllegalArgumentException("scopeType 仅支持 PROBLEM/FAILED/ENDED_CONTEST/TIME_RANGE");
        }
        return type;
    }

    private List<Submission> selectCandidates(RejudgeBatchScope scope, String type) {
        LambdaQueryWrapper<Submission> wrapper = new LambdaQueryWrapper<>();
        switch (type) {
            case SCOPE_PROBLEM -> wrapper.eq(Submission::getProblemId, scope.problemId());
            case SCOPE_FAILED -> wrapper.eq(Submission::getJudgePhase, "FAILED");
            case SCOPE_ENDED_CONTEST -> wrapper.eq(Submission::getContestId, scope.contestId());
            case SCOPE_TIME_RANGE -> wrapper.ge(Submission::getCreateTime, scope.from())
                    .lt(Submission::getCreateTime, scope.to());
            default -> throw new IllegalArgumentException("未知范围");
        }
        // 非主过滤条件也允许叠加（FAILED 常用；其余范围提供时同样生效）。
        if (!SCOPE_PROBLEM.equals(type) && scope.problemId() != null) {
            wrapper.eq(Submission::getProblemId, scope.problemId());
        }
        if (!SCOPE_ENDED_CONTEST.equals(type) && scope.contestId() != null) {
            wrapper.eq(Submission::getContestId, scope.contestId());
        }
        if (!SCOPE_TIME_RANGE.equals(type)) {
            if (scope.from() != null) wrapper.ge(Submission::getCreateTime, scope.from());
            if (scope.to() != null) wrapper.lt(Submission::getCreateTime, scope.to());
        }
        wrapper.orderByAsc(Submission::getId).last("LIMIT " + (MAX_SCAN + 1));
        List<Submission> rows = submissionMapper.selectList(wrapper);
        return rows == null ? List.of() : rows;
    }

    /** 比赛准入：null 表示允许；否则返回拒绝原因。 */
    private String checkContest(Long contestId) {
        int state = contestState(contestId, new HashMap<>());
        return switch (state) {
            case CONTEST_FINALIZED -> "该比赛已固化最终成绩/Rating，按 A 冻结策略禁止重判历史比赛";
            case CONTEST_RUNNING -> "比赛尚未结束，禁止重判";
            case CONTEST_MISSING -> "提交关联的比赛不存在，禁止重判";
            default -> null;
        };
    }

    private int contestState(Long contestId, Map<Long, Integer> cache) {
        if (contestId == null) {
            return CONTEST_ALLOWED;
        }
        Integer cached = cache.get(contestId);
        if (cached != null) {
            return cached;
        }
        ContestSchedule schedule = contestScheduleQuery.findById(contestId).orElse(null);
        int state;
        if (schedule == null) {
            state = CONTEST_MISSING;
        } else if (contestScheduleQuery.isRankFinalized(contestId)) {
            state = CONTEST_FINALIZED;
        } else if (schedule.endTime() == null || !LocalDateTime.now().isAfter(schedule.endTime())) {
            state = CONTEST_RUNNING;
        } else {
            state = CONTEST_ALLOWED;
        }
        cache.put(contestId, state);
        return state;
    }

    // ==================================================================
    // 实体构建 / 重置
    // ==================================================================

    private void validateReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("重判原因不能为空");
        }
        if (reason.length() > 500) {
            throw new IllegalArgumentException("重判原因不能超过 500 字");
        }
    }

    private RejudgeOperation newOperation(Long operatorId, String operatorName, String reason,
                                          String scopeType, String scopeJson, int totalItems, LocalDateTime now) {
        RejudgeOperation operation = new RejudgeOperation();
        operation.setOperatorId(operatorId);
        operation.setOperatorName(operatorName);
        operation.setReason(reason.trim());
        operation.setScopeType(scopeType);
        operation.setScopeJson(scopeJson);
        operation.setStatus(RejudgeOperation.STATUS_RUNNING);
        operation.setTotalItems(totalItems);
        operation.setSucceededItems(0);
        operation.setFailedItems(0);
        operation.setCreatedAt(now);
        operation.setStartedAt(now);
        return operation;
    }

    private RejudgeItem newItem(Long operationId, Submission submission, String digest, LocalDateTime now) {
        RejudgeItem item = new RejudgeItem();
        item.setOperationId(operationId);
        item.setSubmissionId(submission.getId());
        item.setProblemId(submission.getProblemId());
        item.setUserId(submission.getUserId());
        item.setContestId(submission.getContestId());
        item.setVpAttemptId(submission.getVpAttemptId());
        item.setTestcaseDigest(digest);
        item.setOldResultJson(RejudgeResultCodec.toJson(submission));
        item.setStatus(RejudgeItem.STATUS_PENDING);
        item.setCreatedAt(now);
        return item;
    }

    /**
     * 重置为待判。必须用 UpdateWrapper 显式 set null 的列（error_message），
     * 否则 MyBatis-Plus 的默认 NOT_NULL 更新策略会保留旧错误信息。
     */
    private void resetSubmission(Long submissionId, LocalDateTime now) {
        int updated = submissionMapper.update(null, new LambdaUpdateWrapper<Submission>()
                .eq(Submission::getId, submissionId)
                .set(Submission::getStatus, (byte) 0)
                .set(Submission::getJudgePhase, "QUEUED")
                .set(Submission::getScore, 0)
                .set(Submission::getRunTime, 0)
                .set(Submission::getRunMemory, 0)
                .set(Submission::getErrorMessage, null)
                .set(Submission::getUpdateTime, now));
        if (updated != 1) {
            throw new IllegalStateException("提交重置失败 submissionId=" + submissionId);
        }
    }

    private void resetJudgeTask(Long submissionId, LocalDateTime now) {
        JudgeTask task = judgeTaskMapper.selectOne(new LambdaQueryWrapper<JudgeTask>()
                .eq(JudgeTask::getSubmissionId, submissionId));
        if (task == null) {
            task = new JudgeTask();
            task.setSubmissionId(submissionId);
            task.setStatus(JudgeTask.STATUS_PENDING);
            task.setAttemptCount(0);
            task.setGeneration(0);
            task.setScheduledAt(now);
            task.setCreatedAt(now);
            task.setUpdatedAt(now);
            judgeTaskMapper.insert(task);
            return;
        }
        int updated = judgeTaskMapper.update(null, new LambdaUpdateWrapper<JudgeTask>()
                .eq(JudgeTask::getId, task.getId())
                .set(JudgeTask::getStatus, JudgeTask.STATUS_PENDING)
                // 重试预算清零；generation 属于执行代次，只能由 Worker 领取时递增，禁止重置/减小。
                .set(JudgeTask::getAttemptCount, 0)
                .set(JudgeTask::getScheduledAt, now)
                .set(JudgeTask::getLockedAt, null)
                .set(JudgeTask::getCompletedAt, null)
                .set(JudgeTask::getLastError, null)
                .set(JudgeTask::getUpdatedAt, now));
        if (updated != 1) {
            throw new IllegalStateException("判题任务重置失败 submissionId=" + submissionId);
        }
    }

    // ==================================================================
    // 查询/view
    // ==================================================================

    private RejudgeOperationVO loadOperation(Long operationId) {
        RejudgeOperation operation = operationMapper.selectById(operationId);
        if (operation == null) {
            throw new IllegalArgumentException("重判操作不存在");
        }
        List<RejudgeItem> items = itemMapper.selectList(new LambdaQueryWrapper<RejudgeItem>()
                .eq(RejudgeItem::getOperationId, operationId)
                .orderByAsc(RejudgeItem::getId));
        return toVO(operation, items);
    }

    private RejudgeOperationVO toVO(RejudgeOperation op, List<RejudgeItem> items) {
        return new RejudgeOperationVO(
                op.getId(), op.getOperatorId(), op.getOperatorName(), op.getReason(),
                op.getScopeType(), op.getScopeJson(), op.getStatus(),
                nullSafe(op.getTotalItems()), nullSafe(op.getSucceededItems()), nullSafe(op.getFailedItems()),
                op.getCreatedAt(), op.getStartedAt(), op.getFinishedAt(), op.getErrorMessage(),
                items == null ? null : items.stream().map(RejudgeOperationVO.Item::from).toList());
    }

    private static int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    private String scopeJson(RejudgeBatchScope scope, String type) {
        StringBuilder json = new StringBuilder("{\"scopeType\":\"").append(type).append('"');
        if (scope.problemId() != null) json.append(",\"problemId\":").append(scope.problemId());
        if (scope.contestId() != null) json.append(",\"contestId\":").append(scope.contestId());
        if (scope.from() != null) json.append(",\"from\":\"").append(scope.from()).append('"');
        if (scope.to() != null) json.append(",\"to\":\"").append(scope.to()).append('"');
        return json.append('}').toString();
    }

    /** 对判题实际使用的用例做内容摘要，证明本次重判按哪一版用例执行。 */
    static String testCaseDigest(List<TestCaseView> testCases) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (TestCaseView testCase : testCases) {
                String canonical = String.join("|",
                        String.valueOf(testCase.id()),
                        String.valueOf(testCase.caseOrder()),
                        String.valueOf(testCase.score()),
                        String.valueOf(testCase.subtaskNo()),
                        String.valueOf(testCase.status()),
                        String.valueOf(testCase.input()),
                        String.valueOf(testCase.output()));
                digest.update(canonical.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return toHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("测试用例摘要计算失败", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    /** 一次范围评估结果（预览与执行共用）。 */
    private record BatchEvaluation(
            String scopeType,
            int matched,
            List<Submission> eligible,
            Map<Long, String> digestByProblem,
            int blockedFinalized,
            int blockedRunning,
            int blockedVp,
            int blockedPending,
            int blockedNoTestCases,
            int blockedInFlight,
            int blockedMissingContest,
            boolean truncated
    ) {
        String blockedSummary() {
            return "匹配 " + matched + "，可重判 " + eligible.size()
                    + "，已固化 " + blockedFinalized + "，进行中/未结束 " + blockedRunning
                    + "，VP " + blockedVp + "，待判 " + blockedPending
                    + "，在途重判 " + blockedInFlight + "，无测试用例 " + blockedNoTestCases
                    + "，比赛缺失 " + blockedMissingContest;
        }
    }
}
