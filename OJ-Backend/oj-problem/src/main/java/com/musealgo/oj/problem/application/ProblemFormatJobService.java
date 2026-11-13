package com.musealgo.oj.problem.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.problem.entity.Problem;
import com.musealgo.oj.problem.entity.ProblemFormatJob;
import com.musealgo.oj.problem.entity.ProblemFormatJobItem;
import com.musealgo.oj.problem.entity.ProblemStatementVersion;
import com.musealgo.oj.problem.error.ProblemErrorCode;
import com.musealgo.oj.problem.format.IssueSeverity;
import com.musealgo.oj.problem.format.ProblemFormatApplyResult;
import com.musealgo.oj.problem.format.ProblemFormatJobItemVO;
import com.musealgo.oj.problem.format.ProblemFormatJobRequest;
import com.musealgo.oj.problem.format.ProblemFormatJobVO;
import com.musealgo.oj.problem.format.ProblemFormatPreviewItem;
import com.musealgo.oj.problem.format.ProblemFormattedContent;
import com.musealgo.oj.problem.format.ProblemStatementSnapshot;
import com.musealgo.oj.problem.format.StatementIssue;
import com.musealgo.oj.problem.format.quality.StatementQualityChecker;
import com.musealgo.oj.problem.mapper.ProblemFormatJobItemMapper;
import com.musealgo.oj.problem.mapper.ProblemFormatJobMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 批量题面整理任务：受理、查询、取消、失败重试，以及 worker 使用的领取/落库方法。
 * <p>
 * 设计取舍：HTTP 只负责「受理 + 查询」，真正的 AI 调用在固定线程池里跑。
 * 这样不会因为一次整理 30 道题跨过网关 300s 超时（旧同步批量接口的必然结果），
 * 关掉页面也不会丢结果，失败项还能单点重试而不是整批重来。
 * </p>
 */
@Service
public class ProblemFormatJobService {

    private static final Logger log = LoggerFactory.getLogger(ProblemFormatJobService.class);

    public static final String JOB_PENDING = "PENDING";
    public static final String JOB_RUNNING = "RUNNING";
    public static final String JOB_DONE = "DONE";
    public static final String JOB_CANCELED = "CANCELED";
    public static final String JOB_FAILED = "FAILED";

    public static final String ITEM_PENDING = "PENDING";
    public static final String ITEM_RUNNING = "RUNNING";
    public static final String ITEM_APPLIED = "APPLIED";
    public static final String ITEM_REVIEW = "REVIEW";
    public static final String ITEM_FAILED = "FAILED";
    public static final String ITEM_SKIPPED = "SKIPPED";

    /** 单次任务上限：30 道题约 30~60 次模型调用，再多人均等待时间不可接受。 */
    static final int MAX_PROBLEMS_PER_JOB = 30;

    private static final Set<String> ACTIVE_JOB_STATUS = Set.of(JOB_PENDING, JOB_RUNNING);

    private final ProblemFormatJobMapper jobMapper;
    private final ProblemFormatJobItemMapper itemMapper;
    private final ProblemFormatApplicationService formatApplicationService;
    private final ProblemStatementVersionService versionService;
    private final ProblemAccessGuard accessGuard;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public ProblemFormatJobService(ProblemFormatJobMapper jobMapper,
                                   ProblemFormatJobItemMapper itemMapper,
                                   ProblemFormatApplicationService formatApplicationService,
                                   ProblemStatementVersionService versionService,
                                   ProblemAccessGuard accessGuard,
                                   ObjectMapper objectMapper,
                                   PlatformTransactionManager transactionManager,
                                   ApplicationEventPublisher eventPublisher) {
        this.jobMapper = jobMapper;
        this.itemMapper = itemMapper;
        this.formatApplicationService = formatApplicationService;
        this.versionService = versionService;
        this.accessGuard = accessGuard;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.eventPublisher = eventPublisher;
    }

    /** 任务已受理事件：worker 监听后投递到线程池。用事件而不是直接依赖，避免 service 与 worker 循环依赖。 */
    public record ProblemFormatJobCreatedEvent(Long jobId) {
    }

    // ==================== 对外：受理 / 查询 ====================

    public ProblemFormatJobVO create(ProblemFormatJobRequest request) {
        SecurityUtils.requireTeacherOrAdmin();
        Long creatorId = SecurityUtils.getUserId();
        if (creatorId == null) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "未登录，无法创建整理任务");
        }
        List<Long> problemIds = normalizeProblemIds(request);
        // 权限在这里一次性按创建人的真实身份校验：worker 线程没有 SecurityContext，
        // 后续不再重复鉴权，但也不会拿到创建人当时无权管理的题目。
        List<Problem> problems = new ArrayList<>(problemIds.size());
        for (Long problemId : problemIds) {
            problems.add(accessGuard.requireManagedProblem(problemId));
        }
        requireNoActiveJob(creatorId);

        boolean ipMode = request.ipEnabled();
        boolean autoApply = request.autoApplyEnabled();
        Long jobId;
        try {
            jobId = transactionTemplate.execute(status -> {
                ProblemFormatJob job = new ProblemFormatJob();
                job.setCreatorId(creatorId);
                job.setStatus(JOB_PENDING);
                job.setIpMode(ipMode);
                job.setAutoApply(autoApply);
                job.setTotal(problems.size());
                job.setApplied(0);
                job.setReview(0);
                job.setFailed(0);
                job.setSkipped(0);
                job.setCancelRequested(false);
                job.setCreateTime(LocalDateTime.now());
                job.setUpdateTime(LocalDateTime.now());
                jobMapper.insert(job);

                for (Problem problem : problems) {
                    ProblemFormatJobItem item = new ProblemFormatJobItem();
                    item.setJobId(job.getId());
                    item.setProblemId(problem.getId());
                    item.setStatus(ITEM_PENDING);
                    item.setTitle(problem.getTitle());
                    item.setAttempts(0);
                    item.setCreateTime(LocalDateTime.now());
                    item.setUpdateTime(LocalDateTime.now());
                    itemMapper.insert(item);
                }
                return job.getId();
            });
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            // 并发双击：uk_pfj_active_creator 兜住第二次插入
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID,
                    "已有整理任务在进行中，请等待完成或先取消");
        }
        if (jobId == null) {
            throw new IllegalStateException("整理任务创建失败");
        }
        eventPublisher.publishEvent(new ProblemFormatJobCreatedEvent(jobId));
        return detail(jobId);
    }

    public ProblemFormatJobVO detail(Long jobId) {
        ProblemFormatJob job = requireVisibleJob(jobId);
        List<ProblemFormatJobItem> items = itemMapper.selectList(new LambdaQueryWrapper<ProblemFormatJobItem>()
                .eq(ProblemFormatJobItem::getJobId, jobId)
                .orderByAsc(ProblemFormatJobItem::getId));
        List<ProblemFormatJobItemVO> itemViews = new ArrayList<>(items.size());
        for (ProblemFormatJobItem item : items) {
            itemViews.add(toItemVO(item));
        }
        int processed = nz(job.getApplied()) + nz(job.getReview()) + nz(job.getFailed()) + nz(job.getSkipped());
        return new ProblemFormatJobVO(job.getId(), job.getStatus(), Boolean.TRUE.equals(job.getIpMode()),
                Boolean.TRUE.equals(job.getAutoApply()), nz(job.getTotal()), processed, nz(job.getApplied()),
                nz(job.getReview()), nz(job.getFailed()), nz(job.getSkipped()),
                Boolean.TRUE.equals(job.getCancelRequested()), job.getMessage(), job.getCreateTime(),
                job.getFinishTime(), List.copyOf(itemViews));
    }

    /**
     * 单题版本优化：以指定版本为基线创建 1 条任务，产出候选版本等待审阅。
     * <p>不自动应用：候选必须经过人工勾选字段后才写入题面。</p>
     */
    public ProblemFormatJobVO createVersionJob(Long problemId, Long baseVersionId, boolean ipMode) {
        SecurityUtils.requireTeacherOrAdmin();
        Long creatorId = SecurityUtils.getUserId();
        if (creatorId == null) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "未登录，无法创建整理任务");
        }
        Problem problem = accessGuard.requireManagedProblem(problemId);
        ProblemStatementVersion base = baseVersionId == null
                ? versionService.ensureLive(problem)
                : versionService.requireVersion(problemId, baseVersionId);
        if (ProblemStatementVersionService.STATUS_CANDIDATE.equals(base.getStatus())) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "候选版本不能作为优化基线");
        }
        requireNoActiveJob(creatorId);

        Long jobId = transactionTemplate.execute(status -> {
            ProblemFormatJob job = new ProblemFormatJob();
            job.setCreatorId(creatorId);
            job.setStatus(JOB_PENDING);
            job.setIpMode(ipMode);
            job.setAutoApply(false);
            job.setTotal(1);
            job.setApplied(0);
            job.setReview(0);
            job.setFailed(0);
            job.setSkipped(0);
            job.setCancelRequested(false);
            job.setCreateTime(LocalDateTime.now());
            job.setUpdateTime(LocalDateTime.now());
            jobMapper.insert(job);

            ProblemFormatJobItem item = new ProblemFormatJobItem();
            item.setJobId(job.getId());
            item.setProblemId(problemId);
            item.setStatus(ITEM_PENDING);
            item.setTitle(base.getTitle());
            item.setBaseVersionId(base.getId());
            item.setAttempts(0);
            item.setCreateTime(LocalDateTime.now());
            item.setUpdateTime(LocalDateTime.now());
            itemMapper.insert(item);
            return job.getId();
        });
        if (jobId == null) {
            throw new IllegalStateException("整理任务创建失败");
        }
        eventPublisher.publishEvent(new ProblemFormatJobCreatedEvent(jobId));
        return detail(jobId);
    }

    /**
     * 当前登录人正在进行的任务；没有则返回 null。
     * <p>前端关闭面板后重新进入时靠它接回进度，避免「面板关了但任务还在跑」的失联状态。</p>
     */
    public ProblemFormatJobVO activeJob() {
        SecurityUtils.requireTeacherOrAdmin();
        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            return null;
        }
        ProblemFormatJob job = jobMapper.selectOne(new LambdaQueryWrapper<ProblemFormatJob>()
                .eq(ProblemFormatJob::getCreatorId, userId)
                .in(ProblemFormatJob::getStatus, ACTIVE_JOB_STATUS)
                .orderByDesc(ProblemFormatJob::getId)
                .last("LIMIT 1"));
        return job == null ? null : detail(job.getId());
    }

    /** 打开单题审阅：直接读任务里保存的提案，不再调用模型。 */
    public ProblemFormatPreviewItem proposal(Long jobId, Long problemId) {
        requireVisibleJob(jobId);
        ProblemFormatJobItem item = itemMapper.selectOne(new LambdaQueryWrapper<ProblemFormatJobItem>()
                .eq(ProblemFormatJobItem::getJobId, jobId)
                .eq(ProblemFormatJobItem::getProblemId, problemId));
        if (item == null) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_NOT_FOUND, "该题目不在本次任务中");
        }
        if (item.getFormattedJson() == null || item.getFormattedJson().isBlank()) {
            return ProblemFormatPreviewItem.failure(problemId, item.getMessage() == null ? "该题目尚未生成整理结果" : item.getMessage(),
                    item.getErrorCode() == null ? "FORMAT_NOT_READY" : item.getErrorCode());
        }
        ProblemFormattedContent formatted = readJson(item.getFormattedJson(), ProblemFormattedContent.class);
        ProblemStatementSnapshot source = item.getSourceJson() == null ? null
                : readJson(item.getSourceJson(), ProblemStatementSnapshot.class);
        List<StatementIssue> issues = item.getIssuesJson() == null ? List.of()
                : readIssues(item.getIssuesJson());
        return ProblemFormatPreviewItem.success(problemId, formatted, source, item.getBaseHash(), issues);
    }

    public ProblemFormatJobVO cancel(Long jobId) {
        ProblemFormatJob job = requireVisibleJob(jobId);
        if (!ACTIVE_JOB_STATUS.contains(job.getStatus())) {
            return detail(jobId);
        }
        jobMapper.update(null, new LambdaUpdateWrapper<ProblemFormatJob>()
                .eq(ProblemFormatJob::getId, jobId)
                .set(ProblemFormatJob::getCancelRequested, true)
                .set(ProblemFormatJob::getUpdateTime, LocalDateTime.now()));
        if (JOB_PENDING.equals(job.getStatus())) {
            // 还没被 worker 领取，直接终止；RUNNING 的情况交给 worker 在下一题前检查
            markCanceled(jobId, ITEM_PENDING);
        }
        return detail(jobId);
    }

    /** 失败项重试：只把 FAILED 项放回队列，已完成/待审的项不动，避免重复消耗模型调用。 */
    public ProblemFormatJobVO retryFailed(Long jobId) {
        ProblemFormatJob job = requireVisibleJob(jobId);
        if (ACTIVE_JOB_STATUS.contains(job.getStatus())) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "任务仍在进行中，请先等待完成或取消");
        }
        List<ProblemFormatJobItem> failedItems = itemMapper.selectList(new LambdaQueryWrapper<ProblemFormatJobItem>()
                .eq(ProblemFormatJobItem::getJobId, jobId)
                .eq(ProblemFormatJobItem::getStatus, ITEM_FAILED));
        if (failedItems.isEmpty()) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "没有需要重试的题目");
        }
        transactionTemplate.executeWithoutResult(status -> {
            for (ProblemFormatJobItem item : failedItems) {
                itemMapper.update(null, new LambdaUpdateWrapper<ProblemFormatJobItem>()
                        .eq(ProblemFormatJobItem::getId, item.getId())
                        .set(ProblemFormatJobItem::getStatus, ITEM_PENDING)
                        .set(ProblemFormatJobItem::getMessage, null)
                        .set(ProblemFormatJobItem::getErrorCode, null)
                        .set(ProblemFormatJobItem::getUpdateTime, LocalDateTime.now()));
            }
            jobMapper.update(null, new LambdaUpdateWrapper<ProblemFormatJob>()
                    .eq(ProblemFormatJob::getId, jobId)
                    .set(ProblemFormatJob::getStatus, JOB_PENDING)
                    .set(ProblemFormatJob::getCancelRequested, false)
                    .set(ProblemFormatJob::getMessage, "重试失败项")
                    .set(ProblemFormatJob::getFinishTime, null)
                    .set(ProblemFormatJob::getUpdateTime, LocalDateTime.now()));
        });
        refreshCounters(jobId);
        eventPublisher.publishEvent(new ProblemFormatJobCreatedEvent(jobId));
        return detail(jobId);
    }

    // ==================== 对内：worker 使用 ====================

    /** 原子领取：只有把 PENDING 改成 RUNNING 成功的那次调用才真正执行任务。 */
    public boolean claim(Long jobId) {
        int updated = jobMapper.update(null, new LambdaUpdateWrapper<ProblemFormatJob>()
                .eq(ProblemFormatJob::getId, jobId)
                .eq(ProblemFormatJob::getStatus, JOB_PENDING)
                .set(ProblemFormatJob::getStatus, JOB_RUNNING)
                .set(ProblemFormatJob::getUpdateTime, LocalDateTime.now()));
        return updated > 0;
    }

    public ProblemFormatJob findJob(Long jobId) {
        return jobMapper.selectById(jobId);
    }

    public List<ProblemFormatJobItem> pendingItems(Long jobId) {
        return itemMapper.selectList(new LambdaQueryWrapper<ProblemFormatJobItem>()
                .eq(ProblemFormatJobItem::getJobId, jobId)
                .eq(ProblemFormatJobItem::getStatus, ITEM_PENDING)
                .orderByAsc(ProblemFormatJobItem::getId));
    }

    public boolean isCancelRequested(Long jobId) {
        ProblemFormatJob job = jobMapper.selectById(jobId);
        return job != null && Boolean.TRUE.equals(job.getCancelRequested());
    }

    /**
     * 处理单题：生成提案 → 质量检查 → （可选）直接应用。
     * <p>任何异常都收敛成该题的 FAILED 状态，不中断整个任务。</p>
     */
    public void processItem(ProblemFormatJob job, ProblemFormatJobItem item) {
        int claimed = itemMapper.update(null, new LambdaUpdateWrapper<ProblemFormatJobItem>()
                .eq(ProblemFormatJobItem::getId, item.getId())
                .eq(ProblemFormatJobItem::getStatus, ITEM_PENDING)
                .set(ProblemFormatJobItem::getStatus, ITEM_RUNNING)
                .set(ProblemFormatJobItem::getAttempts, nz(item.getAttempts()) + 1)
                .set(ProblemFormatJobItem::getUpdateTime, LocalDateTime.now()));
        if (claimed == 0) {
            // 已被其他执行者领取或已结束，跳过以免重复调用模型
            return;
        }
        try {
            Problem problem = formatApplicationService.requireProblemForJob(item.getProblemId());
            // 版本模式下必须以「基线版本」为输入：教师可能选的是两版之前的题面
            ProblemStatementSnapshot source = item.getBaseVersionId() == null
                    ? formatApplicationService.snapshot(problem)
                    : versionService.snapshotOf(item.getProblemId(), item.getBaseVersionId());
            ProblemFormatApplicationService.Proposal proposal = formatApplicationService
                    .generateProposal(source, Boolean.TRUE.equals(job.getIpMode()), job.getCreatorId());

            if (item.getBaseVersionId() != null) {
                // 单题版本优化：只产出候选版本，绝不自动写题面
                ProblemStatementVersion candidate = versionService.createCandidate(item.getProblemId(),
                        item.getBaseVersionId(), job.getId(), job.getCreatorId(), proposal);
                markItem(item.getId(), ITEM_REVIEW,
                        "已生成候选版本 v" + candidate.getVersionNo() + "，等待审阅",
                        null, proposal, null, candidate.getId());
                return;
            }

            List<StatementIssue> issues = proposal.issues();
            boolean blocking = StatementQualityChecker.hasBlockingIssue(issues);
            boolean sampleRisk = StatementQualityChecker.hasSampleRisk(issues);
            // 正文大幅缩短、样例数量变化都需要人看一眼，自动应用一律转待审
            boolean needsHuman = StatementQualityChecker.hasContentLoss(issues);

            if (Boolean.TRUE.equals(job.getAutoApply()) && !blocking && !sampleRisk && !needsHuman) {
                // 原文没写时限/内存时，模型给的是平台默认值；自动应用不采纳，避免把 256MB 改成 512MB
                List<String> autoFields = new ArrayList<>(List.of(
                        "title", "content", "inputDesc", "outputDesc", "hint", "samples"));
                if (StatementQualityChecker.declaresTimeLimit(proposal.source())) {
                    autoFields.add("timeLimit");
                }
                if (StatementQualityChecker.declaresMemoryLimit(proposal.source())) {
                    autoFields.add("memoryLimit");
                }
                // 批量自动应用同样要留版本：应用前保证有基线，应用后记录新内容。
                // 记录失败只告警，不能因为审计信息丢失就把已经写好的题面判成失败。
                try {
                    versionService.ensureLive(problem, job.getCreatorId());
                } catch (RuntimeException e) {
                    log.warn("批量整理记录基线版本失败 problemId={}", item.getProblemId(), e);
                }
                ProblemFormatApplyResult applied = formatApplicationService.applyFormattedProposal(
                        item.getProblemId(), proposal.baseHash(), proposal.formatted(), autoFields);
                try {
                    versionService.recordSnapshot(item.getProblemId(), job.getCreatorId(), "批量整理自动应用");
                } catch (RuntimeException e) {
                    log.warn("批量整理记录结果版本失败 problemId={}", item.getProblemId(), e);
                }
                markItem(item.getId(), ITEM_APPLIED, "已自动应用：" + String.join("、", applied.appliedFields()),
                        null, proposal, String.join(",", applied.appliedFields()), null);
            } else {
                String reason = blocking ? "存在阻断问题，等待人工审阅"
                        : sampleRisk ? "样例存在风险，等待人工审阅"
                        : needsHuman ? "正文明显缩短，等待人工确认"
                        : "已生成，等待人工审阅";
                // 待审项也落成候选版本：审阅入口统一到题面工作区，不再需要单独的对照弹窗
                Long candidateId = null;
                try {
                    ProblemStatementVersion live = versionService.ensureLive(problem, job.getCreatorId());
                    candidateId = versionService.createCandidate(item.getProblemId(), live.getId(),
                            job.getId(), job.getCreatorId(), proposal).getId();
                } catch (RuntimeException e) {
                    log.warn("批量整理生成候选版本失败 problemId={}", item.getProblemId(), e);
                }
                markItem(item.getId(), ITEM_REVIEW, reason, null, proposal, null, candidateId);
            }
        } catch (Exception e) {
            log.warn("题面整理失败 problemId={} jobId={}", item.getProblemId(), job.getId(), e);
            String message = e instanceof BusinessException business ? business.getMessage() : "处理失败，请稍后重试";
            String code = e instanceof BusinessException business ? business.getErrorCode().key() : "INTERNAL_ERROR";
            itemMapper.update(null, new LambdaUpdateWrapper<ProblemFormatJobItem>()
                    .eq(ProblemFormatJobItem::getId, item.getId())
                    .set(ProblemFormatJobItem::getStatus, ITEM_FAILED)
                    .set(ProblemFormatJobItem::getMessage, truncate(message, 480))
                    .set(ProblemFormatJobItem::getErrorCode, code)
                    .set(ProblemFormatJobItem::getUpdateTime, LocalDateTime.now()));
        } finally {
            refreshCounters(job.getId());
        }
    }

    /** 取消后把未开始的项统一标为跳过，已完成/已应用的项保持原状。 */
    public void markRemainingSkipped(Long jobId) {        itemMapper.update(null, new LambdaUpdateWrapper<ProblemFormatJobItem>()
                .eq(ProblemFormatJobItem::getJobId, jobId)
                .in(ProblemFormatJobItem::getStatus, ITEM_PENDING, ITEM_RUNNING)
                .set(ProblemFormatJobItem::getStatus, ITEM_SKIPPED)
                .set(ProblemFormatJobItem::getMessage, "任务已取消")
                .set(ProblemFormatJobItem::getUpdateTime, LocalDateTime.now()));
        refreshCounters(jobId);
    }

    public void finish(Long jobId) {
        ProblemFormatJob job = jobMapper.selectById(jobId);
        if (job == null) {
            return;
        }
        boolean canceled = Boolean.TRUE.equals(job.getCancelRequested());
        String message = String.format("成功 %d 题，待审 %d 题，失败 %d 题，跳过 %d 题",
                nz(job.getApplied()), nz(job.getReview()), nz(job.getFailed()), nz(job.getSkipped()));
        jobMapper.update(null, new LambdaUpdateWrapper<ProblemFormatJob>()
                .eq(ProblemFormatJob::getId, jobId)
                .set(ProblemFormatJob::getStatus, canceled ? JOB_CANCELED : JOB_DONE)
                .set(ProblemFormatJob::getMessage, message)
                .set(ProblemFormatJob::getFinishTime, LocalDateTime.now())
                .set(ProblemFormatJob::getUpdateTime, LocalDateTime.now()));
    }

    /** 任务级失败：未完成的项统一标为 FAILED，教师可以对失败项单点重试。 */
    public void fail(Long jobId, String message) {
        itemMapper.update(null, new LambdaUpdateWrapper<ProblemFormatJobItem>()
                .eq(ProblemFormatJobItem::getJobId, jobId)
                .in(ProblemFormatJobItem::getStatus, ITEM_PENDING, ITEM_RUNNING)
                .set(ProblemFormatJobItem::getStatus, ITEM_FAILED)
                .set(ProblemFormatJobItem::getMessage, truncate(message, 480))
                .set(ProblemFormatJobItem::getUpdateTime, LocalDateTime.now()));
        refreshCounters(jobId);
        jobMapper.update(null, new LambdaUpdateWrapper<ProblemFormatJob>()
                .eq(ProblemFormatJob::getId, jobId)
                .set(ProblemFormatJob::getStatus, JOB_FAILED)
                .set(ProblemFormatJob::getMessage, truncate(message, 255))
                .set(ProblemFormatJob::getFinishTime, LocalDateTime.now())
                .set(ProblemFormatJob::getUpdateTime, LocalDateTime.now()));
    }

    /**
     * 启动恢复：把长时间没有更新的 RUNNING 任务判定为中断。
     * 阈值而不是「所有 RUNNING」是为了不误伤另一个实例正在执行的任务。
     */
    public int failOrphaned(LocalDateTime olderThan, String message) {
        List<ProblemFormatJob> stale = jobMapper.selectList(new LambdaQueryWrapper<ProblemFormatJob>()
                .eq(ProblemFormatJob::getStatus, JOB_RUNNING)
                .lt(ProblemFormatJob::getUpdateTime, olderThan));
        for (ProblemFormatJob job : stale) {
            fail(job.getId(), message);
        }
        return stale.size();
    }

    private void markCanceled(Long jobId, String targetStatus) {
        itemMapper.update(null, new LambdaUpdateWrapper<ProblemFormatJobItem>()
                .eq(ProblemFormatJobItem::getJobId, jobId)
                .eq(ProblemFormatJobItem::getStatus, targetStatus)
                .set(ProblemFormatJobItem::getStatus, ITEM_SKIPPED)
                .set(ProblemFormatJobItem::getMessage, "任务已取消")
                .set(ProblemFormatJobItem::getUpdateTime, LocalDateTime.now()));
        refreshCounters(jobId);
        jobMapper.update(null, new LambdaUpdateWrapper<ProblemFormatJob>()
                .eq(ProblemFormatJob::getId, jobId)
                .set(ProblemFormatJob::getStatus, JOB_CANCELED)
                .set(ProblemFormatJob::getMessage, "任务已取消")
                .set(ProblemFormatJob::getFinishTime, LocalDateTime.now())
                .set(ProblemFormatJob::getUpdateTime, LocalDateTime.now()));
    }

    private void markItem(Long itemId, String status, String message, String errorCode,
                          ProblemFormatApplicationService.Proposal proposal, String appliedFields,
                          Long resultVersionId) {
        itemMapper.update(null, new LambdaUpdateWrapper<ProblemFormatJobItem>()
                .eq(ProblemFormatJobItem::getId, itemId)
                .set(ProblemFormatJobItem::getStatus, status)
                .set(ProblemFormatJobItem::getMessage, truncate(message, 480))
                .set(ProblemFormatJobItem::getErrorCode, errorCode)
                .set(ProblemFormatJobItem::getTitle, truncate(proposal.formatted().title(), 128))
                .set(ProblemFormatJobItem::getSourceJson, writeJson(proposal.source()))
                .set(ProblemFormatJobItem::getFormattedJson, writeJson(proposal.formatted()))
                .set(ProblemFormatJobItem::getIssuesJson, writeJson(proposal.issues()))
                .set(ProblemFormatJobItem::getBaseHash, proposal.baseHash())
                .set(ProblemFormatJobItem::getAppliedFields, appliedFields)
                .set(ProblemFormatJobItem::getResultVersionId, resultVersionId)
                .set(ProblemFormatJobItem::getUpdateTime, LocalDateTime.now()));
    }

    /** 以 item 状态为准重算计数：单 worker 顺序执行，重算比分散自增更不容易漂移。 */
    public void refreshCounters(Long jobId) {
        List<ProblemFormatJobItem> items = itemMapper.selectList(new LambdaQueryWrapper<ProblemFormatJobItem>()
                .eq(ProblemFormatJobItem::getJobId, jobId));
        int applied = 0;
        int review = 0;
        int failed = 0;
        int skipped = 0;
        for (ProblemFormatJobItem item : items) {
            switch (item.getStatus()) {
                case ITEM_APPLIED -> applied++;
                case ITEM_REVIEW -> review++;
                case ITEM_FAILED -> failed++;
                case ITEM_SKIPPED -> skipped++;
                default -> {
                    // PENDING / RUNNING 尚未完成，不计数
                }
            }
        }
        jobMapper.update(null, new LambdaUpdateWrapper<ProblemFormatJob>()
                .eq(ProblemFormatJob::getId, jobId)
                .set(ProblemFormatJob::getApplied, applied)
                .set(ProblemFormatJob::getReview, review)
                .set(ProblemFormatJob::getFailed, failed)
                .set(ProblemFormatJob::getSkipped, skipped)
                .set(ProblemFormatJob::getUpdateTime, LocalDateTime.now()));
    }

    // ==================== 内部工具 ====================

    private List<Long> normalizeProblemIds(ProblemFormatJobRequest request) {
        if (request == null || request.problemIds() == null || request.problemIds().isEmpty()) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "请至少选择一道题目");
        }
        List<Long> ids = request.problemIds().stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "题目 ID 无效");
        }
        if (ids.size() > MAX_PROBLEMS_PER_JOB) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID,
                    "单次最多整理 " + MAX_PROBLEMS_PER_JOB + " 道题目");
        }
        return ids;
    }

    /** 同一教师同时只允许一个未完成任务：重复点击不会重复消耗模型调用。 */
    private void requireNoActiveJob(Long creatorId) {
        Long active = jobMapper.selectCount(new LambdaQueryWrapper<ProblemFormatJob>()
                .eq(ProblemFormatJob::getCreatorId, creatorId)
                .in(ProblemFormatJob::getStatus, ACTIVE_JOB_STATUS));
        if (active != null && active > 0) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID,
                    "已有整理任务在进行中，请等待完成或先取消");
        }
    }

    private ProblemFormatJob requireVisibleJob(Long jobId) {
        if (jobId == null) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "任务 ID 无效");
        }
        ProblemFormatJob job = jobMapper.selectById(jobId);
        if (job == null) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_NOT_FOUND, "整理任务不存在");
        }
        SecurityUtils.requireTeacherOrAdmin();
        Long userId = SecurityUtils.getUserId();
        if (!SecurityUtils.isAdmin() && !Objects.equals(userId, job.getCreatorId())) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_NOT_FOUND, "整理任务不存在");
        }
        return job;
    }

    private ProblemFormatJobItemVO toItemVO(ProblemFormatJobItem item) {
        List<StatementIssue> issues = item.getIssuesJson() == null ? List.of() : readIssues(item.getIssuesJson());
        int errors = (int) issues.stream().filter(issue -> issue.severity() == IssueSeverity.ERROR).count();
        int warnings = (int) issues.stream().filter(issue -> issue.severity() == IssueSeverity.WARN).count();
        List<String> applied = item.getAppliedFields() == null || item.getAppliedFields().isBlank()
                ? List.of()
                : List.of(item.getAppliedFields().split(","));
        return new ProblemFormatJobItemVO(item.getProblemId(), item.getTitle(), item.getStatus(),
                item.getMessage(), item.getErrorCode(), nz(item.getAttempts()), errors, warnings,
                item.getFormattedJson() != null && !item.getFormattedJson().isBlank(), applied,
                item.getBaseVersionId(), item.getResultVersionId());
    }

    private String writeJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("任务快照序列化失败", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("任务快照解析失败", e);
        }
    }

    private List<StatementIssue> readIssues(String json) {
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<StatementIssue>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 供测试与诊断：当前是否有活跃任务。 */
    public boolean hasActiveJob(Long creatorId) {
        Long active = jobMapper.selectCount(new LambdaQueryWrapper<ProblemFormatJob>()
                .eq(ProblemFormatJob::getCreatorId, creatorId)
                .in(ProblemFormatJob::getStatus, ACTIVE_JOB_STATUS));
        return active != null && active > 0;
    }

    public List<Long> claimableJobIds() {
        return jobMapper.selectList(new LambdaQueryWrapper<ProblemFormatJob>()
                        .eq(ProblemFormatJob::getStatus, JOB_PENDING)
                        .orderByAsc(ProblemFormatJob::getId)
                        .last("LIMIT 10"))
                .stream().map(ProblemFormatJob::getId).toList();
    }
}
