package com.musealgo.oj.ai.draft;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musealgo.oj.ai.TeacherWorkbenchService;
import com.musealgo.oj.ai.dto.AiCompletionResult;
import com.musealgo.oj.ai.entity.AiUsage;
import com.musealgo.oj.ai.entity.ProblemDraft;
import com.musealgo.oj.ai.error.AiErrorCode;
import com.musealgo.oj.ai.mapper.ProblemDraftMapper;
import com.musealgo.oj.ai.usage.AiUsageRecorder;
import com.musealgo.oj.api.problem.ProblemDraftPublisher;
import com.musealgo.oj.api.problem.DraftProblemCommand;
import com.musealgo.oj.api.problem.DraftProblemSample;
import com.musealgo.oj.api.problem.SimilarProblemView;
import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.ai.draft.dto.DraftCreateRequest;
import com.musealgo.oj.ai.draft.dto.DraftRewriteRequest;
import com.musealgo.oj.ai.draft.dto.DraftSaveRequest;
import com.musealgo.oj.ai.draft.vo.DraftDetailVO;
import com.musealgo.oj.ai.draft.vo.DraftPageVO;
import com.musealgo.oj.ai.draft.vo.DraftPromptVO;
import com.musealgo.oj.ai.draft.vo.DraftSaveResultVO;
import com.musealgo.oj.ai.draft.vo.DraftUsageVO;
import com.musealgo.oj.ai.draft.vo.DraftVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 题面生成草稿：受理、查询、取消、重试、定向重写、落库，以及 worker 使用的领取与执行方法。
 * <p>
 * HTTP 只做「受理 + 查询 + 短操作」，模型调用在 {@link ProblemDraftWorker} 的固定线程池里跑。
 * 这样一次生成不会因为跨过网关读超时而丢掉结果，教师关掉页面也能从历史里找回来。
 * </p>
 */
@Service
public class ProblemDraftService {

    private static final Logger log = LoggerFactory.getLogger(ProblemDraftService.class);

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String READY = "READY";
    public static final String FAILED = "FAILED";
    public static final String CANCELED = "CANCELED";

    public static final String PHASE_QUEUED = "QUEUED";
    public static final String PHASE_PROMPTING = "PROMPTING";
    public static final String PHASE_GENERATING = "GENERATING";
    public static final String PHASE_PARSING = "PARSING";
    public static final String PHASE_DONE = "DONE";

    /** 同时进行的草稿上限：一次最多 3 方案，避免连续点击把模型调用堆成队列。 */
    static final int MAX_ACTIVE_DRAFTS = 3;
    static final int MAX_INSTRUCTION = 500;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_SAMPLES = 8;
    private static final int MAX_TAGS = 6;
    private static final int MAX_TAG_LENGTH = 20;
    private static final int MAX_SAMPLE_TEXT = 8_000;
    private static final int MAX_SAMPLE_EXPLANATION = 500;
    private static final int MAX_TITLE_CODE_POINTS = 128;
    private static final String PROMPT_STATEMENT = "teacher-draft-statement";
    private static final String PROMPT_REWRITE = "teacher-draft-field-rewrite";
    private static final String DRAFT_SOURCE = "AI 命题工坊";

    private final ProblemDraftMapper draftMapper;
    private final TeacherWorkbenchService workbench;
    private final AiUsageRecorder usageRecorder;
    private final ProblemDraftPublisher publisher;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public ProblemDraftService(ProblemDraftMapper draftMapper,
                               TeacherWorkbenchService workbench,
                               AiUsageRecorder usageRecorder,
                               ProblemDraftPublisher publisher,
                               ObjectMapper objectMapper,
                               PlatformTransactionManager transactionManager,
                               ApplicationEventPublisher eventPublisher) {
        this.draftMapper = draftMapper;
        this.workbench = workbench;
        this.usageRecorder = usageRecorder;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.eventPublisher = eventPublisher;
    }

    /** 草稿已受理：worker 监听后投递线程池。用事件解耦，避免 service 与 worker 循环依赖。 */
    public record DraftCreatedEvent(Long draftId) {
    }

    // ==================== 对外：受理 / 查询 ====================

    public List<DraftVO> create(DraftCreateRequest request) {
        SecurityUtils.requireTeacherOrAdmin();
        Long creatorId = requireUserId();
        if (request == null || request.brief() == null) {
            throw new BusinessException(AiErrorCode.AI_INPUT_INVALID, "请先填写命题简报");
        }
        DraftBrief brief = request.brief();
        List<String> errors = brief.validate();
        if (!errors.isEmpty()) {
            throw new BusinessException(AiErrorCode.AI_INPUT_INVALID, String.join("；", errors));
        }
        DraftBrief normalized = brief.normalized();
        int variants = request.variantsOrDefault();
        long active = countActive(creatorId);
        if (active + variants > MAX_ACTIVE_DRAFTS) {
            throw new BusinessException(AiErrorCode.AI_INPUT_INVALID,
                    "同时进行的生成任务不能超过 " + MAX_ACTIVE_DRAFTS + " 个，请等待当前任务结束或先删除");
        }
        String groupId = UUID.randomUUID().toString();
        String briefJson = writeJson(normalized);
        LocalDateTime now = LocalDateTime.now();
        List<Long> ids = transactionTemplate.execute(status -> {
            List<Long> created = new ArrayList<>(variants);
            for (int index = 1; index <= variants; index++) {
                ProblemDraft draft = new ProblemDraft();
                draft.setCreatorId(creatorId);
                draft.setGroupId(groupId);
                draft.setVariantNo(index);
                draft.setStatus(PENDING);
                draft.setPhase(PHASE_QUEUED);
                draft.setBriefJson(briefJson);
                draft.setBriefSummary(truncate(normalized.summary(), 255));
                draft.setAttempts(0);
                draft.setPromptTokens(0);
                draft.setCompletionTokens(0);
                draft.setLatencyMs(0);
                draft.setCreateTime(now);
                draft.setUpdateTime(now);
                draftMapper.insert(draft);
                created.add(draft.getId());
            }
            return created;
        });
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(AiErrorCode.AI_UPSTREAM_FAILED, "草稿受理失败，请重试");
        }
        ids.forEach(id -> eventPublisher.publishEvent(new DraftCreatedEvent(id)));
        return ids.stream().map(this::requireDraft).map(this::toVO).toList();
    }

    public DraftPageVO list(int page, int size, String status, String groupId) {
        SecurityUtils.requireTeacherOrAdmin();
        Long creatorId = requireUserId();
        if (groupId != null && !groupId.isBlank()) {
            // 方案组查询不分页：一次生成最多 3 条，前端按组轮询，分页只会增加一次无意义的总数查询
            List<ProblemDraft> rows = draftMapper.selectList(new LambdaQueryWrapper<ProblemDraft>()
                    .eq(ProblemDraft::getCreatorId, creatorId)
                    .eq(ProblemDraft::getGroupId, groupId.trim())
                    .orderByAsc(ProblemDraft::getVariantNo));
            List<DraftVO> items = rows.stream().map(this::toVO).toList();
            return new DraftPageVO(items.size(), 1, Math.max(1, items.size()), items);
        }
        int safePage = Math.max(1, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        LambdaQueryWrapper<ProblemDraft> filter = new LambdaQueryWrapper<ProblemDraft>()
                .eq(ProblemDraft::getCreatorId, creatorId)
                .eq(status != null && !status.isBlank(), ProblemDraft::getStatus, status);
        Long total = draftMapper.selectCount(filter);
        List<ProblemDraft> rows = draftMapper.selectList(filter.clone()
                .orderByDesc(ProblemDraft::getId)
                .last("LIMIT " + safeSize + " OFFSET " + (long) (safePage - 1) * safeSize));
        return new DraftPageVO(total == null ? 0 : total, safePage, safeSize,
                rows.stream().map(this::toVO).toList());
    }

    public DraftDetailVO detail(Long draftId) {
        ProblemDraft draft = requireVisible(draftId);
        return toDetail(draft);
    }

    public DraftVO cancel(Long draftId) {
        ProblemDraft draft = requireVisible(draftId);
        if (!PENDING.equals(draft.getStatus()) || !PHASE_QUEUED.equals(draft.getPhase())) {
            throw new BusinessException(AiErrorCode.AI_STATE_CONFLICT,
                    "模型调用已发出，无法取消；可等待结果或删除这条草稿");
        }
        int updated = draftMapper.update(null, new LambdaUpdateWrapper<ProblemDraft>()
                .eq(ProblemDraft::getId, draftId)
                .eq(ProblemDraft::getStatus, PENDING)
                .eq(ProblemDraft::getPhase, PHASE_QUEUED)
                .set(ProblemDraft::getStatus, CANCELED)
                .set(ProblemDraft::getPhase, PHASE_DONE)
                .set(ProblemDraft::getMessage, "已取消")
                .set(ProblemDraft::getFinishTime, LocalDateTime.now())
                .set(ProblemDraft::getUpdateTime, LocalDateTime.now()));
        if (updated == 0) {
            // 查询与更新之间被 worker 领走：按真实状态回 409，而不是返回一个「已取消」的假象
            throw new BusinessException(AiErrorCode.AI_STATE_CONFLICT,
                    "模型调用已发出，无法取消；可等待结果或删除这条草稿");
        }
        return toVO(requireDraft(draftId));
    }

    public DraftVO retry(Long draftId) {
        ProblemDraft draft = requireVisible(draftId);
        if (!FAILED.equals(draft.getStatus())) {
            throw new BusinessException(AiErrorCode.AI_STATE_CONFLICT, "只有失败的草稿可以重试");
        }
        draftMapper.update(null, new LambdaUpdateWrapper<ProblemDraft>()
                .eq(ProblemDraft::getId, draftId)
                .set(ProblemDraft::getStatus, PENDING)
                .set(ProblemDraft::getPhase, PHASE_QUEUED)
                .set(ProblemDraft::getErrorCode, null)
                .set(ProblemDraft::getMessage, "已重新排队")
                .set(ProblemDraft::getFinishTime, null)
                .set(ProblemDraft::getUpdateTime, LocalDateTime.now()));
        eventPublisher.publishEvent(new DraftCreatedEvent(draftId));
        return toVO(requireDraft(draftId));
    }

    /**
     * 删除草稿。
     * <p>
     * RUNNING 也允许删除：取消窗口只有「排队中」这一瞬，如果删除也禁止，教师就没有任何
     * 丢弃这次生成的手段。代价是已发出的模型调用照常计费，前端确认框必须写明这一点。
     * 被删除后 worker 的后续更新都是按 id 的空更新，不会复活记录。
     * </p>
     */
    public void delete(Long draftId) {
        requireVisible(draftId);
        draftMapper.deleteById(draftId);
    }

    /** 保存教师的手工修改；同时重跑质量检查，保证详情里看到的问题与内容一致。 */
    public DraftDetailVO updateContent(Long draftId, DraftContent content) {
        ProblemDraft draft = requireVisible(draftId);
        if (!READY.equals(draft.getStatus())) {
            throw new BusinessException(AiErrorCode.AI_STATE_CONFLICT, "只有已生成完成的草稿可以编辑");
        }
        if (content == null) {
            throw new BusinessException(AiErrorCode.AI_INPUT_INVALID, "内容不能为空");
        }
        DraftBrief brief = readBrief(draft);
        DraftContent normalized = normalize(content);
        List<DraftIssue> issues = DraftQualityChecker.check(normalized, brief);
        draftMapper.update(null, new LambdaUpdateWrapper<ProblemDraft>()
                .eq(ProblemDraft::getId, draftId)
                .set(ProblemDraft::getContentJson, writeJson(normalized))
                .set(ProblemDraft::getIssuesJson, writeJson(issues))
                .set(ProblemDraft::getTitle, truncate(normalized.title(), MAX_TITLE_CODE_POINTS))
                // 内容改了就不再对应上一次落库的题目：清掉标记，下次保存会新建一道，
                // 否则界面会显示「已保存为 #旧ID」而实际题库里没有这次修改
                .set(ProblemDraft::getProblemId, null)
                .set(ProblemDraft::getSavedFields, null)
                .set(ProblemDraft::getMessage, "已保存修改，请重新落库")
                .set(ProblemDraft::getUpdateTime, LocalDateTime.now()));
        return toDetail(requireDraft(draftId));
    }

    /** 定向重写：产物是子草稿，不覆盖父草稿，便于对比「改写前后」。 */
    public DraftDetailVO rewrite(Long draftId, DraftRewriteRequest request) {
        ProblemDraft parent = requireVisible(draftId);
        if (!READY.equals(parent.getStatus())) {
            throw new BusinessException(AiErrorCode.AI_STATE_CONFLICT, "只有生成完成的草稿可以定向重写");
        }
        String target = request == null ? null : request.targetField();
        if (target == null || !DraftContent.REWRITABLE_FIELDS.contains(target)) {
            throw new BusinessException(AiErrorCode.AI_INPUT_INVALID, "只能重写题目描述、样例、题名、标签或提示");
        }
        String instruction = request.instruction() == null ? "" : request.instruction().trim();
        if (instruction.isEmpty()) {
            throw new BusinessException(AiErrorCode.AI_INPUT_INVALID, "请填写这次改写的要求");
        }
        if (instruction.codePointCount(0, instruction.length()) > MAX_INSTRUCTION) {
            throw new BusinessException(AiErrorCode.AI_INPUT_INVALID,
                    "改写要求不能超过 " + MAX_INSTRUCTION + " 个字符");
        }
        Long creatorId = requireUserId();
        if (countActive(creatorId) >= MAX_ACTIVE_DRAFTS) {
            throw new BusinessException(AiErrorCode.AI_INPUT_INVALID,
                    "同时进行的生成任务不能超过 " + MAX_ACTIVE_DRAFTS + " 个，请等待当前任务结束");
        }
        LocalDateTime now = LocalDateTime.now();
        ProblemDraft child = new ProblemDraft();
        child.setCreatorId(creatorId);
        child.setGroupId(UUID.randomUUID().toString());
        child.setVariantNo(1);
        child.setStatus(PENDING);
        child.setPhase(PHASE_QUEUED);
        child.setBriefJson(parent.getBriefJson());
        child.setBriefSummary(parent.getBriefSummary());
        child.setInstruction(instruction);
        child.setTargetField(target);
        child.setParentId(parent.getId());
        child.setTitle(parent.getTitle());
        child.setAttempts(0);
        child.setPromptTokens(0);
        child.setCompletionTokens(0);
        child.setLatencyMs(0);
        child.setCreateTime(now);
        child.setUpdateTime(now);
        draftMapper.insert(child);
        eventPublisher.publishEvent(new DraftCreatedEvent(child.getId()));
        return toDetail(requireDraft(child.getId()));
    }

    /**
     * 落库为私有题目。
     * <p>
     * 阻断问题默认拦截；教师明确确认（{@code allowIssues=true}）后放行，并在任务说明里留下记录。
     * 同一草稿重复保存时返回已有题目，除非 {@code force=true}，避免双击产生两道重复题。
     * </p>
     */
    public DraftSaveResultVO save(Long draftId, DraftSaveRequest request) {
        ProblemDraft draft = requireVisible(draftId);
        if (!READY.equals(draft.getStatus())) {
            throw new BusinessException(AiErrorCode.AI_STATE_CONFLICT, "只有生成完成的草稿可以保存为题目");
        }
        DraftContent content = readContent(draft);
        if (content == null) {
            throw new BusinessException(AiErrorCode.AI_STATE_CONFLICT, "草稿内容缺失，无法保存");
        }
        List<DraftIssue> issues = readIssues(draft);
        List<String> fields = selectedFields(request, content);
        List<DraftIssue> blocking = DraftQualityChecker.blockingIssues(issues, new LinkedHashSet<>(fields));
        boolean allowIssues = request != null && request.allowIssuesOrDefault();
        if (!blocking.isEmpty() && !allowIssues) {
            throw new BusinessException(AiErrorCode.AI_INPUT_INVALID,
                    "存在 " + blocking.size() + " 项阻断问题，请先修复或在确认后继续保存");
        }
        boolean force = request != null && request.forceOrDefault();
        boolean alreadySaved = draft.getProblemId() != null;
        if (alreadySaved && !force) {
            return new DraftSaveResultVO(draft.getProblemId(), false, readSavedFields(draft), issues);
        }
        if (!alreadySaved) {
            // 先原子占位再建题：双击或两个标签页同时保存时，只有一次能通过 isNull 条件
            int claimed = draftMapper.update(null, new LambdaUpdateWrapper<ProblemDraft>()
                    .eq(ProblemDraft::getId, draftId)
                    .isNull(ProblemDraft::getProblemId)
                    .set(ProblemDraft::getProblemId, -1L)
                    .set(ProblemDraft::getUpdateTime, LocalDateTime.now()));
            if (claimed == 0) {
                ProblemDraft latest = requireDraft(draftId);
                return new DraftSaveResultVO(latest.getProblemId(), false, readSavedFields(latest), issues);
            }
        }
        // 只把真正写了非空值的字段算作「已写入」：勾了但内容为空的字段调用方会看到它没有生效
        List<String> applied = new ArrayList<>(fields.size());
        for (String field : fields) {
            if ("title".equals(field) || "content".equals(field) || hasValue(field, content)) {
                applied.add(field);
            }
        }
        int timeLimit = clamp(request == null || request.timeLimit() == null
                ? nvl(content.timeLimit(), 1000) : request.timeLimit(), 100, 30_000);
        int memoryLimit = clamp(request == null || request.memoryLimit() == null
                ? nvl(content.memoryLimit(), 512) : request.memoryLimit(), 16, 1024);
        Long creatorId = requireUserId();
        Long problemId;
        try {
            problemId = publisher.createDraft(creatorId, new DraftProblemCommand(
                    truncate(content.title(), MAX_TITLE_CODE_POINTS),
                    content.content(),
                    applied.contains("inputDesc") ? content.inputDesc() : "",
                    applied.contains("outputDesc") ? content.outputDesc() : "",
                    applied.contains("hint") ? content.hint() : "",
                    content.difficultyScore(),
                    timeLimit,
                    memoryLimit,
                    applied.contains("tags") ? content.tags() : List.of(),
                    applied.contains("samples") ? content.samples().stream()
                            .map(sample -> new DraftProblemSample(sample.input(), sample.output()))
                            .toList() : List.of(),
                    DRAFT_SOURCE));
        } catch (RuntimeException failure) {
            // 建题失败必须把占位还原，否则草稿永远停在 -1、界面显示已保存但题库里没有
            if (!alreadySaved) {
                draftMapper.update(null, new LambdaUpdateWrapper<ProblemDraft>()
                        .eq(ProblemDraft::getId, draftId)
                        .eq(ProblemDraft::getProblemId, -1L)
                        .set(ProblemDraft::getProblemId, null)
                        .set(ProblemDraft::getUpdateTime, LocalDateTime.now()));
            }
            throw failure;
        }
        draftMapper.update(null, new LambdaUpdateWrapper<ProblemDraft>()
                .eq(ProblemDraft::getId, draftId)
                .set(ProblemDraft::getProblemId, problemId)
                .set(ProblemDraft::getSavedFields, String.join(",", applied))
                .set(ProblemDraft::getMessage, "已保存为私有题目 #" + problemId)
                .set(ProblemDraft::getUpdateTime, LocalDateTime.now()));
        return new DraftSaveResultVO(problemId, true, applied, issues);
    }

    public DraftPromptVO promptPreview(DraftBrief brief) {
        DraftBrief target = brief == null ? DraftBrief.defaults() : brief;
        List<String> errors = target.validate();
        if (!errors.isEmpty()) {
            throw new BusinessException(AiErrorCode.AI_INPUT_INVALID, String.join("；", errors));
        }
        return new DraftPromptVO(systemPrompt(PROMPT_STATEMENT), DraftPromptBuilder.statementPrompt(target));
    }

    public List<SimilarProblemView> similar(Long draftId) {
        ProblemDraft draft = requireVisible(draftId);
        DraftContent content = readContent(draft);
        String title = content == null ? draft.getTitle() : content.title();
        if (title == null || title.isBlank()) {
            return List.of();
        }
        return publisher.findSimilar(draft.getCreatorId(), title, 5);
    }

    // ==================== 对内：worker 使用 ====================

    /** 原子领取：只有把 PENDING 改成 RUNNING 成功的那次调用才真正执行。 */
    public boolean claim(Long draftId) {
        int updated = draftMapper.update(null, new LambdaUpdateWrapper<ProblemDraft>()
                .eq(ProblemDraft::getId, draftId)
                .eq(ProblemDraft::getStatus, PENDING)
                .set(ProblemDraft::getStatus, RUNNING)
                .set(ProblemDraft::getPhase, PHASE_PROMPTING)
                .setSql("attempts = attempts + 1")
                .set(ProblemDraft::getUpdateTime, LocalDateTime.now()));
        return updated > 0;
    }

    public ProblemDraft find(Long draftId) {
        return draftId == null ? null : draftMapper.selectById(draftId);
    }

    public List<Long> claimableDraftIds() {
        return draftMapper.selectList(new LambdaQueryWrapper<ProblemDraft>()
                        .eq(ProblemDraft::getStatus, PENDING)
                        .orderByAsc(ProblemDraft::getId)
                        .last("LIMIT 10"))
                .stream().map(ProblemDraft::getId).toList();
    }

    /**
     * 执行一次生成。任何异常都收敛成该草稿的 FAILED，不向外抛，避免拖垮整个线程池任务。
     */
    public void process(ProblemDraft draft) {
        Long draftId = draft.getId();
        LocalDateTime started = LocalDateTime.now();
        try {
            DraftBrief brief = readBrief(draft);
            ProblemDraft parent = draft.getParentId() == null ? null : draftMapper.selectById(draft.getParentId());
            DraftContent base = parent == null ? null : readContent(parent);
            if (draft.getParentId() != null && base == null) {
                markFailed(draftId, "AI_INPUT_INVALID", "作为改写基线的草稿已不存在，请重新生成");
                return;
            }
            String userPrompt = draft.getTargetField() == null
                    ? DraftPromptBuilder.statementPrompt(brief)
                    : DraftPromptBuilder.rewritePrompt(brief, base, draft.getTargetField(), draft.getInstruction());
            String promptKey = draft.getTargetField() == null ? PROMPT_STATEMENT : PROMPT_REWRITE;

            updatePhase(draftId, PHASE_GENERATING);
            AiCompletionResult call = workbench.teacherWorkbenchJsonDetailed(promptKey, userPrompt);
            recordUsage(draft.getCreatorId(), draftId, userPrompt, call, started);

            if (!call.success()) {
                markFailed(draftId, failureCode(call), failureMessage(call));
                return;
            }
            updatePhase(draftId, PHASE_PARSING);
            DraftContent parsed;
            try {
                parsed = DraftStatementParser.parse(call.content(), brief);
            } catch (DraftStatementParser.DraftParseException parseFailure) {
                markFailed(draftId, AiErrorCode.AI_RESPONSE_INVALID.key(), parseFailure.getMessage());
                return;
            }
            DraftContent merged = base == null ? parsed : base.replaceField(draft.getTargetField(), parsed);
            // 出口统一收敛：模型可能给出重复/空/超量样例，数据库层没有兜底
            DraftContent result = normalize(merged);
            List<DraftIssue> issues = DraftQualityChecker.check(result, brief);
            String message = base != null && result.textOf(draft.getTargetField()).equals(base.textOf(draft.getTargetField()))
                    ? "模型没有改动目标字段，请换一种说法再试"
                    : "已生成，请人工复核";
            draftMapper.update(null, new LambdaUpdateWrapper<ProblemDraft>()
                    .eq(ProblemDraft::getId, draftId)
                    .set(ProblemDraft::getStatus, READY)
                    .set(ProblemDraft::getPhase, PHASE_DONE)
                    .set(ProblemDraft::getTitle, truncate(result.title(), MAX_TITLE_CODE_POINTS))
                    .set(ProblemDraft::getContentJson, writeJson(result))
                    .set(ProblemDraft::getIssuesJson, writeJson(issues))
                    .set(ProblemDraft::getErrorCode, null)
                    .set(ProblemDraft::getMessage, message)
                    .set(ProblemDraft::getFinishTime, LocalDateTime.now())
                    .set(ProblemDraft::getUpdateTime, LocalDateTime.now()));
        } catch (Exception failure) {
            log.warn("题面生成失败 draftId={}", draftId, failure);
            markFailed(draftId, "INTERNAL_ERROR", "生成过程出现异常，请重试");
        }
    }


    /**
     * 启动恢复：长时间没有更新的 RUNNING 草稿判定为中断。
     * 用阈值而不是「所有 RUNNING」，避免误伤另一个实例正在跑的草稿。
     */
    public int failOrphaned(LocalDateTime olderThan, String message) {
        List<ProblemDraft> stale = draftMapper.selectList(new LambdaQueryWrapper<ProblemDraft>()
                .eq(ProblemDraft::getStatus, RUNNING)
                .lt(ProblemDraft::getUpdateTime, olderThan));
        for (ProblemDraft draft : stale) {
            markFailed(draft.getId(), "AI_DRAFT_TIMEOUT", message);
        }
        return stale.size();
    }

    // ==================== 内部工具 ====================

    private void updatePhase(Long draftId, String phase) {
        draftMapper.update(null, new LambdaUpdateWrapper<ProblemDraft>()
                .eq(ProblemDraft::getId, draftId)
                .set(ProblemDraft::getPhase, phase)
                .set(ProblemDraft::getUpdateTime, LocalDateTime.now()));
    }

    private void markStatus(Long draftId, String status, String message, String errorCode) {
        draftMapper.update(null, new LambdaUpdateWrapper<ProblemDraft>()
                .eq(ProblemDraft::getId, draftId)
                .set(ProblemDraft::getStatus, status)
                .set(ProblemDraft::getPhase, PHASE_DONE)
                .set(ProblemDraft::getMessage, truncate(message, 512))
                .set(ProblemDraft::getErrorCode, errorCode)
                .set(ProblemDraft::getFinishTime, LocalDateTime.now())
                .set(ProblemDraft::getUpdateTime, LocalDateTime.now()));
    }

    private void markFailed(Long draftId, String errorCode, String message) {
        markStatus(draftId, FAILED, message, errorCode);
    }

    private static String failureCode(AiCompletionResult call) {
        String type = call.errorType() == null ? "" : call.errorType();
        return switch (type) {
            case "TIMEOUT" -> "UPSTREAM_TIMEOUT";
            case "INVALID_RESPONSE", "RESPONSE_TOO_LARGE" -> AiErrorCode.AI_RESPONSE_INVALID.key();
            case "OUTPUT_LIMIT" -> AiErrorCode.AI_OUTPUT_LIMIT.key();
            case "CONTENT_FILTER" -> AiErrorCode.AI_RESPONSE_FILTERED.key();
            default -> AiErrorCode.AI_UPSTREAM_FAILED.key();
        };
    }

    private static String failureMessage(AiCompletionResult call) {
        String type = call.errorType() == null ? "" : call.errorType();
        return switch (type) {
            case "TIMEOUT" -> "模型响应超时，请重试";
            case "INVALID_RESPONSE", "RESPONSE_TOO_LARGE" -> "模型返回内容格式不正确，请重试";
            case "OUTPUT_LIMIT" -> "模型输出达到上限被截断，请减少样例组数或缩短要求后重试";
            case "CONTENT_FILTER" -> "模型未能完成本次生成，请调整命题要求";
            default -> "AI 服务暂时不可用，请稍后重试";
        };
    }

    private void recordUsage(Long userId, Long draftId, String prompt, AiCompletionResult call,
                             LocalDateTime started) {
        try {
            int promptTokens;
            int completionTokens;
            boolean estimated;
            if (call.cached()) {
                promptTokens = 0;
                completionTokens = 0;
                estimated = false;
            } else if (call.usage() != null) {
                promptTokens = call.usage().promptTokens();
                completionTokens = call.usage().completionTokens();
                estimated = false;
            } else {
                promptTokens = com.musealgo.oj.ai.AiService.estimateTokens(prompt);
                completionTokens = com.musealgo.oj.ai.AiService.estimateTokens(call.content());
                estimated = true;
            }
            int latency = (int) Math.max(0, java.time.Duration.between(started, LocalDateTime.now()).toMillis());
            usageRecorder.record(userId, null, "DRAFT_STATEMENT",
                    promptTokens, completionTokens, estimated, latency,
                    call.success() ? AiUsage.STATUS_SUCCESS : AiUsage.STATUS_FAIL,
                    call.success() ? null : "题面生成失败", call.source());
            // 累加而不是覆盖：重试与定向重写都会产生新的模型开销，详情里应能看出累计成本
            draftMapper.update(null, new LambdaUpdateWrapper<ProblemDraft>()
                    .eq(ProblemDraft::getId, draftId)
                    .setSql("prompt_tokens = prompt_tokens + " + promptTokens)
                    .setSql("completion_tokens = completion_tokens + " + completionTokens)
                    .set(ProblemDraft::getLatencyMs, latency)
                    .set(ProblemDraft::getUpdateTime, LocalDateTime.now()));
        } catch (RuntimeException failure) {
            log.warn("题面生成用量记账失败 draftId={}：{}", draftId, failure.getClass().getSimpleName());
        }
    }

    private DraftDetailVO toDetail(ProblemDraft draft) {
        DraftContent content = readContent(draft);
        List<DraftIssue> issues = readIssues(draft);
        DraftBrief brief = readBrief(draft);
        DraftVO vo = DraftVO.of(draft, content, issues, LocalDateTime.now());
        DraftPromptVO prompt = buildPrompt(draft, brief);
        // 没有独立存 estimated 标志：token 为 0 说明没拿到供应商用量（失败或未调用），据此提示
        boolean estimated = nvl(draft.getPromptTokens(), 0) == 0;
        return new DraftDetailVO(vo, brief, content, issues, prompt,
                new DraftUsageVO(nvl(draft.getPromptTokens(), 0), nvl(draft.getCompletionTokens(), 0),
                        nvl(draft.getLatencyMs(), 0), estimated),
                draft.getErrorCode());
    }

    /**
     * 详情里的提示词是「按当前输入重新拼出来的」，不是发出时的原文快照。
     * 题面生成没有原文基线，重建结果与真实调用一致；定向重写依赖父草稿当前内容，
     * 父草稿被编辑后这里会跟着变，因此只作为解释用途，不作为审计凭据。
     */
    private DraftPromptVO buildPrompt(ProblemDraft draft, DraftBrief brief) {
        if (draft.getTargetField() == null) {
            return new DraftPromptVO(systemPrompt(PROMPT_STATEMENT), DraftPromptBuilder.statementPrompt(brief));
        }
        ProblemDraft parent = draft.getParentId() == null ? null : draftMapper.selectById(draft.getParentId());
        DraftContent base = parent == null ? null : readContent(parent);
        if (base == null) {
            return new DraftPromptVO(systemPrompt(PROMPT_REWRITE), "（基线草稿已不存在，无法重建提示词）");
        }
        return new DraftPromptVO(systemPrompt(PROMPT_REWRITE),
                DraftPromptBuilder.rewritePrompt(brief, base, draft.getTargetField(), draft.getInstruction()));
    }

    private String systemPrompt(String key) {
        String rendered = workbench.renderPrompt(key);
        return rendered == null ? "" : rendered;
    }

    private DraftVO toVO(ProblemDraft draft) {
        return DraftVO.of(draft, readContent(draft), readIssues(draft), LocalDateTime.now());
    }

    private DraftBrief readBrief(ProblemDraft draft) {
        DraftBrief brief = readJson(draft.getBriefJson(), DraftBrief.class);
        return brief == null ? DraftBrief.defaults() : brief;
    }

    private DraftContent readContent(ProblemDraft draft) {
        return readJson(draft.getContentJson(), DraftContent.class);
    }

    private List<DraftIssue> readIssues(ProblemDraft draft) {
        if (draft == null || draft.getIssuesJson() == null || draft.getIssuesJson().isBlank()) {
            return List.of();
        }
        try {
            DraftIssue[] parsed = objectMapper.readValue(draft.getIssuesJson(), DraftIssue[].class);
            return parsed == null ? List.of() : List.of(parsed);
        } catch (Exception e) {
            log.warn("草稿质量问题解析失败 draftId={}", draft == null ? null : draft.getId());
            return List.of();
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("草稿 JSON 解析失败 type={}", type.getSimpleName());
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("草稿内容序列化失败", e);
        }
    }

    /** 落库与回写前统一收敛：丢空对、去重、限量，避免把脏数据带进题库。 */
    static DraftContent normalize(DraftContent content) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (String tag : content.tags()) {
            String cleaned = tag == null ? "" : tag.replaceAll("[\\[\\]\"'#]", "").trim();
            if (cleaned.isEmpty() || tags.size() >= MAX_TAGS) {
                continue;
            }
            tags.add(cleaned.codePointCount(0, cleaned.length()) > MAX_TAG_LENGTH
                    ? cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_TAG_LENGTH))
                    : cleaned);
        }
        List<DraftSample> samples = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (DraftSample sample : content.samples()) {
            if (sample == null) {
                continue;
            }
            String input = limit(sample.input(), MAX_SAMPLE_TEXT);
            String output = limit(sample.output(), MAX_SAMPLE_TEXT);
            if (input.isBlank() && output.isBlank()) {
                continue;
            }
            if (!seen.add(input.strip() + '\u0000' + output.strip())) {
                continue;
            }
            samples.add(new DraftSample(input, output, limit(sample.explanation(), MAX_SAMPLE_EXPLANATION)));
            if (samples.size() >= MAX_SAMPLES) {
                break;
            }
        }
        return new DraftContent(limit(content.title(), MAX_TITLE_CODE_POINTS), content.content(),
                content.inputDesc(), content.outputDesc(), content.hint(),
                snapScore(nvl(content.difficultyScore(), 1200)), List.copyOf(tags),
                List.copyOf(samples), clamp(nvl(content.timeLimit(), 1000), 100, 30_000),
                clamp(nvl(content.memoryLimit(), 512), 16, 1024));
    }

    private List<String> selectedFields(DraftSaveRequest request, DraftContent content) {
        List<String> requested = request == null || request.fields() == null ? List.of() : request.fields();
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        if (requested.isEmpty()) {
            fields.addAll(DraftContent.SAVABLE_FIELDS);
        } else {
            for (String field : requested) {
                if (field != null && DraftContent.SAVABLE_FIELDS.contains(field)) {
                    fields.add(field);
                }
            }
        }
        // 题名与正文是题目的必填项：即使前端漏勾也必须写入，否则建题必然失败
        fields.add("title");
        fields.add("content");
        return DraftContent.SAVABLE_FIELDS.stream().filter(fields::contains).toList();
    }

    /** 该字段是否有可写入的值；空字符串与空列表视为「勾了但没有内容」。 */
    private static boolean hasValue(String field, DraftContent content) {
        return switch (field) {
            case "inputDesc" -> !content.inputDesc().isBlank();
            case "outputDesc" -> !content.outputDesc().isBlank();
            case "hint" -> !content.hint().isBlank();
            case "tags" -> !content.tags().isEmpty();
            case "samples" -> !content.samples().isEmpty();
            default -> true;
        };
    }

    /** 题库按 100 分步长录入：把模型或手工输入的 1350 之类分值吸附到最近刻度。 */
    private static int snapScore(int score) {
        int snapped = Math.round(score / 100f) * 100;
        return clamp(snapped, DraftBrief.MIN_SCORE, DraftBrief.MAX_SCORE);
    }

    private List<String> readSavedFields(ProblemDraft draft) {
        if (draft.getSavedFields() == null || draft.getSavedFields().isBlank()) {
            return List.of();
        }
        return Arrays.stream(draft.getSavedFields().split(",")).filter(value -> !value.isBlank()).toList();
    }

    private ProblemDraft requireDraft(Long draftId) {
        ProblemDraft draft = draftMapper.selectById(draftId);
        if (draft == null) {
            throw new BusinessException(AiErrorCode.AI_DRAFT_NOT_FOUND);
        }
        return draft;
    }

    private ProblemDraft requireVisible(Long draftId) {
        if (draftId == null) {
            throw new BusinessException(AiErrorCode.AI_INPUT_INVALID, "草稿 ID 无效");
        }
        ProblemDraft draft = draftMapper.selectById(draftId);
        if (draft == null) {
            throw new BusinessException(AiErrorCode.AI_DRAFT_NOT_FOUND);
        }
        SecurityUtils.requireTeacherOrAdmin();
        Long userId = SecurityUtils.getUserId();
        if (!SecurityUtils.isAdmin() && !Objects.equals(userId, draft.getCreatorId())) {
            // 他人草稿统一按「不存在」返回，避免通过状态码探测别人是否生成过题目
            throw new BusinessException(AiErrorCode.AI_DRAFT_NOT_FOUND);
        }
        return draft;
    }

    private long countActive(Long creatorId) {
        Long active = draftMapper.selectCount(new LambdaQueryWrapper<ProblemDraft>()
                .eq(ProblemDraft::getCreatorId, creatorId)
                .in(ProblemDraft::getStatus, PENDING, RUNNING));
        return active == null ? 0 : active;
    }

    private static Long requireUserId() {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            throw new BusinessException(AiErrorCode.AI_INPUT_INVALID, "未登录，无法使用题面生成");
        }
        return userId;
    }

    private static int nvl(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String truncate(String value, int maxCodePoints) {
        if (value == null) {
            return null;
        }
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    private static String limit(String value, int maxCodePoints) {
        return value == null ? "" : truncate(value, maxCodePoints);
    }
}
