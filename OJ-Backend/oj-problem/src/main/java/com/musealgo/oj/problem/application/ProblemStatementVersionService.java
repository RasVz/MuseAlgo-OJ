package com.musealgo.oj.problem.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.problem.entity.Problem;
import com.musealgo.oj.problem.entity.ProblemStatementVersion;
import com.musealgo.oj.problem.error.ProblemErrorCode;
import com.musealgo.oj.problem.format.FormatSample;
import com.musealgo.oj.problem.format.IssueSeverity;
import com.musealgo.oj.problem.format.ProblemFormatApplyResult;
import com.musealgo.oj.problem.format.ProblemFormatPreviewItem;
import com.musealgo.oj.problem.format.ProblemFormattedContent;
import com.musealgo.oj.problem.format.ProblemStatementHasher;
import com.musealgo.oj.problem.format.ProblemStatementSnapshot;
import com.musealgo.oj.problem.format.StatementIssue;
import com.musealgo.oj.problem.format.StatementVersionApplyRequest;
import com.musealgo.oj.problem.format.StatementVersionDetailVO;
import com.musealgo.oj.problem.format.StatementVersionVO;
import com.musealgo.oj.problem.format.quality.StatementQualityChecker;
import com.musealgo.oj.problem.mapper.ProblemStatementVersionMapper;
import com.musealgo.oj.problem.service.ProblemService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 题面版本管理：版本快照、候选版本审阅、历史版本启用。
 * <p>
 * 设计要点：
 * </p>
 * <ol>
 *   <li>版本是<b>不可变快照</b>，唯一例外是 CANDIDATE 被应用时提升为 LIVE（会写入实际落库的内容）；</li>
 *   <li>同一道题只允许一个 LIVE 版本，由 {@code live_problem_id} 生成列的唯一索引保证；</li>
 *   <li>「应用候选」只写选中字段，未选字段保持题目当前值，避免把用户后来的手工修改回退成基线内容；</li>
 *   <li>版本列表不返回正文（正文走详情接口），避免几十个版本把响应撑到几百 KB。</li>
 * </ol>
 */
@Service
public class ProblemStatementVersionService {

    private static final Logger log = LoggerFactory.getLogger(ProblemStatementVersionService.class);

    public static final String STATUS_LIVE = "LIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";
    public static final String STATUS_CANDIDATE = "CANDIDATE";
    public static final String STATUS_DISCARDED = "DISCARDED";

    public static final String SOURCE_INITIAL = "INITIAL";
    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_AI = "AI";
    public static final String SOURCE_AI_APPLIED = "AI_APPLIED";

    private static final List<String> ALL_FIELDS = List.of(
            "title", "content", "inputDesc", "outputDesc", "hint", "timeLimit", "memoryLimit", "samples");
    private static final Set<String> ALL_FIELD_SET = Set.copyOf(ALL_FIELDS);
    private static final int SUMMARY_LENGTH = 80;

    private final ProblemStatementVersionMapper versionMapper;
    private final ProblemFormatApplicationService formatApplicationService;
    private final ProblemAccessGuard accessGuard;
    private final ProblemService problemService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ProblemStatementVersionService(ProblemStatementVersionMapper versionMapper,
                                          ProblemFormatApplicationService formatApplicationService,
                                          ProblemAccessGuard accessGuard,
                                          ProblemService problemService,
                                          ObjectMapper objectMapper,
                                          PlatformTransactionManager transactionManager) {
        this.versionMapper = versionMapper;
        this.formatApplicationService = formatApplicationService;
        this.accessGuard = accessGuard;
        this.problemService = problemService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ==================== 读取 ====================

    public List<StatementVersionVO> list(Long problemId) {
        Problem problem = accessGuard.requireManagedProblem(problemId);
        ensureLive(problem);
        List<ProblemStatementVersion> versions = versionMapper.selectList(
                new LambdaQueryWrapper<ProblemStatementVersion>()
                        .eq(ProblemStatementVersion::getProblemId, problemId)
                        .orderByDesc(ProblemStatementVersion::getVersionNo));
        String currentHash = ProblemStatementHasher.hash(formatApplicationService.snapshot(problem));
        List<StatementVersionVO> result = new ArrayList<>(versions.size());
        for (ProblemStatementVersion version : versions) {
            boolean live = STATUS_LIVE.equals(version.getStatus());
            boolean drifted = live && !currentHash.equals(ProblemStatementHasher.hash(toSnapshot(version)));
            result.add(toVO(version, live, drifted));
        }
        return List.copyOf(result);
    }

    public StatementVersionDetailVO detail(Long problemId, Long versionId) {
        accessGuard.requireManagedProblem(problemId);
        ProblemStatementVersion version = requireVersion(problemId, versionId);
        return new StatementVersionDetailVO(version.getId(), version.getProblemId(), version.getVersionNo(),
                version.getStatus(), version.getSource(), version.getTitle(), nullToEmpty(version.getContent()),
                nullToEmpty(version.getInputDesc()), nullToEmpty(version.getOutputDesc()),
                nullToEmpty(version.getHint()), version.getTimeLimit(), version.getMemoryLimit(),
                readSamples(version.getSamplesJson()), readIssues(version.getIssuesJson()),
                version.getBaseVersionId(), version.getJobId(), version.getNote(), version.getCreatedBy(),
                version.getCreateTime());
    }

    /**
     * 候选版本的审阅数据：原文取基线版本，结果为候选内容。
     * <p>与前端的「预览」接口同一结构，工作台因此可以复用同一套对照与勾选逻辑。</p>
     */
    public ProblemFormatPreviewItem proposal(Long problemId, Long versionId) {
        Problem problem = accessGuard.requireManagedProblem(problemId);
        ProblemStatementVersion candidate = requireVersion(problemId, versionId);
        ProblemStatementSnapshot base = candidate.getBaseVersionId() == null
                ? formatApplicationService.snapshot(problem)
                : toSnapshot(requireVersion(problemId, candidate.getBaseVersionId()));
        ProblemFormattedContent formatted = new ProblemFormattedContent(
                candidate.getTitle(), nullToEmpty(candidate.getContent()),
                nullToEmpty(candidate.getInputDesc()), nullToEmpty(candidate.getOutputDesc()),
                nullToEmpty(candidate.getHint()), candidate.getTimeLimit(), candidate.getMemoryLimit(),
                readSamples(candidate.getSamplesJson()));
        List<StatementIssue> issues = readIssues(candidate.getIssuesJson());
        if (issues.isEmpty()) {
            issues = StatementQualityChecker.check(base, formatted);
        }
        return ProblemFormatPreviewItem.success(problemId, formatted, base,
                ProblemStatementHasher.hash(base), issues);
    }

    // ==================== 写入 ====================

    /** 首次访问版本页时把当前题面固化为 v1，让后续所有操作都有明确基线。 */
    public ProblemStatementVersion ensureLive(Problem problem) {
        return ensureLive(problem, SecurityUtils.getUserId());
    }

    /** worker 侧版本：没有请求上下文，用任务创建人记录归属。 */
    public ProblemStatementVersion ensureLive(Problem problem, Long actorId) {
        ProblemStatementVersion live = findLive(problem.getId());
        if (live != null) {
            return live;
        }
        ProblemStatementSnapshot snapshot = formatApplicationService.snapshot(problem);
        return transactionTemplate.execute(status -> {
            ProblemStatementVersion existing = findLive(problem.getId());
            if (existing != null) {
                return existing;
            }
            ProblemStatementVersion version = new ProblemStatementVersion();
            version.setProblemId(problem.getId());
            version.setVersionNo(nextVersionNo(problem.getId()));
            version.setStatus(STATUS_LIVE);
            version.setSource(SOURCE_INITIAL);
            fillFromSnapshot(version, snapshot);
            version.setNote("初始题面");
            version.setCreatedBy(actorId);
            version.setCreateTime(LocalDateTime.now());
            version.setUpdateTime(LocalDateTime.now());
            versionMapper.insert(version);
            return version;
        });
    }

    /**
     * 把题目当前内容记录成新的 LIVE 版本（无鉴权，供后台任务在写库后调用）。
     * <p>批量自动应用也要留下版本，否则那一次改动在历史里查不到、也回退不了。</p>
     */
    public void recordSnapshot(Long problemId, Long actorId, String note) {
        Problem problem = requireProblem(problemId);
        ProblemStatementSnapshot current = formatApplicationService.snapshot(problem);
        transactionTemplate.executeWithoutResult(status -> {
            archiveLive(problemId);
            ProblemStatementVersion version = new ProblemStatementVersion();
            version.setProblemId(problemId);
            version.setVersionNo(nextVersionNo(problemId));
            version.setStatus(STATUS_LIVE);
            version.setSource(SOURCE_AI_APPLIED);
            fillFromSnapshot(version, current);
            version.setNote(note);
            version.setCreatedBy(actorId);
            version.setCreateTime(LocalDateTime.now());
            version.setUpdateTime(LocalDateTime.now());
            versionMapper.insert(version);
        });
    }

    /** 把当前题面记录为新版本；与 LIVE 内容一致时不产生新行。 */
    public StatementVersionVO recordCurrent(Long problemId) {
        SecurityUtils.requireTeacherOrAdmin();
        Problem problem = accessGuard.requireManagedProblem(problemId);
        ProblemStatementVersion live = ensureLive(problem);
        ProblemStatementSnapshot current = formatApplicationService.snapshot(problem);
        if (ProblemStatementHasher.hash(current).equals(ProblemStatementHasher.hash(toSnapshot(live)))) {
            return toVO(live, true, false);
        }
        ProblemStatementVersion created = transactionTemplate.execute(status -> {
            archiveLive(problemId);
            ProblemStatementVersion version = new ProblemStatementVersion();
            version.setProblemId(problemId);
            version.setVersionNo(nextVersionNo(problemId));
            version.setStatus(STATUS_LIVE);
            version.setSource(SOURCE_MANUAL);
            fillFromSnapshot(version, current);
            version.setNote("记录当前题面");
            version.setCreatedBy(SecurityUtils.getUserId());
            version.setCreateTime(LocalDateTime.now());
            version.setUpdateTime(LocalDateTime.now());
            versionMapper.insert(version);
            return version;
        });
        if (created == null) {
            throw new IllegalStateException("版本保存失败");
        }
        return toVO(created, true, false);
    }

    /**
     * 把历史版本设为当前：写回题目并切换 LIVE 标记。
     * <p>会覆盖题目当前内容，调用方（前端）必须先做二次确认。</p>
     */
    public StatementVersionVO activate(Long problemId, Long versionId) {
        SecurityUtils.requireTeacherOrAdmin();
        Problem problem = accessGuard.requireManagedProblem(problemId);
        ProblemStatementVersion target = requireVersion(problemId, versionId);
        if (STATUS_CANDIDATE.equals(target.getStatus())) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "待审候选版本请先审阅后再应用");
        }
        if (STATUS_LIVE.equals(target.getStatus())) {
            return toVO(target, true, false);
        }
        ProblemStatementVersion activated = transactionTemplate.execute(status -> {
            archiveLive(problemId);
            versionMapper.update(null, new LambdaUpdateWrapper<ProblemStatementVersion>()
                    .eq(ProblemStatementVersion::getId, target.getId())
                    .set(ProblemStatementVersion::getStatus, STATUS_LIVE)
                    .set(ProblemStatementVersion::getUpdateTime, LocalDateTime.now()));
            ProblemStatementSnapshot snapshot = toSnapshot(target);
            writeSnapshotToProblem(problem, snapshot, ALL_FIELDS);
            if (!problemService.updateById(problem)) {
                throw new IllegalStateException("题目保存失败");
            }
            return target;
        });
        if (activated == null) {
            throw new IllegalStateException("版本启用失败");
        }
        return toVO(activated, true, false);
    }

    /**
     * 应用候选版本：只把选中字段写进题目，随后候选行提升为 LIVE。
     * <p>
     * 未选字段取题目当前值而不是基线值：用户可能在生成候选之后又手工改过别处，
     * 按基线合并会把这些修改悄悄回退。
     * </p>
     */
    public StatementVersionVO applyCandidate(Long problemId, Long versionId, StatementVersionApplyRequest request) {
        SecurityUtils.requireTeacherOrAdmin();
        Problem problem = accessGuard.requireManagedProblem(problemId);
        ProblemStatementVersion candidate = requireVersion(problemId, versionId);
        if (!STATUS_CANDIDATE.equals(candidate.getStatus())) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "只有待审候选版本可以应用");
        }
        ProblemFormattedContent formatted = request == null || request.formatted() == null
                ? contentOf(candidate)
                : request.formatted();
        List<String> fields = normalizeFields(request == null ? null : request.fields());

        // 服务端闸门：按实际写入字段检查阻断问题，前端取消勾选可以绕过某个字段，但不能带着阻断写入
        ProblemStatementSnapshot base = candidate.getBaseVersionId() == null
                ? formatApplicationService.snapshot(problem)
                : toSnapshot(requireVersion(problemId, candidate.getBaseVersionId()));
        List<StatementIssue> issues = StatementQualityChecker.check(base, formatted);
        if (StatementQualityChecker.hasBlockingIssue(issues, new LinkedHashSet<>(fields))) {
            StatementIssue blocking = issues.stream()
                    .filter(issue -> issue.severity() == IssueSeverity.ERROR && fields.contains(issue.field()))
                    .findFirst().orElseThrow();
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID,
                    "存在阻断问题，无法应用：" + blocking.message());
        }

        ProblemFormatApplyResult applied = formatApplicationService.applyFieldsForVersion(problemId, formatted, fields);
        ProblemStatementSnapshot after = formatApplicationService.snapshot(requireProblem(problemId));
        ProblemStatementVersion promoted = transactionTemplate.execute(status -> {
            archiveLive(problemId);
            versionMapper.update(null, new LambdaUpdateWrapper<ProblemStatementVersion>()
                    .eq(ProblemStatementVersion::getId, candidate.getId())
                    .set(ProblemStatementVersion::getStatus, STATUS_LIVE)
                    .set(ProblemStatementVersion::getSource, SOURCE_AI_APPLIED)
                    .set(ProblemStatementVersion::getTitle, after.title())
                    .set(ProblemStatementVersion::getContent, after.content())
                    .set(ProblemStatementVersion::getInputDesc, after.inputDesc())
                    .set(ProblemStatementVersion::getOutputDesc, after.outputDesc())
                    .set(ProblemStatementVersion::getHint, after.hint())
                    .set(ProblemStatementVersion::getTimeLimit, after.timeLimitMs())
                    .set(ProblemStatementVersion::getMemoryLimit, after.memoryLimitMb())
                    .set(ProblemStatementVersion::getSamplesJson, writeSamples(after.samples()))
                    .set(ProblemStatementVersion::getIssuesJson, writeIssues(issues))
                    .set(ProblemStatementVersion::getNote,
                            "应用 " + String.join("、", applied.appliedFields()) + "（共 " + fields.size() + " 项）")
                    .set(ProblemStatementVersion::getUpdateTime, LocalDateTime.now()));
            return candidate;
        });
        if (promoted == null) {
            throw new IllegalStateException("版本应用失败");
        }
        return toVO(promoted, true, false);
    }

    /** 丢弃候选版本：只改状态，不物理删除，便于追溯「曾经生成过但没采纳」。 */
    public void discardCandidate(Long problemId, Long versionId) {
        SecurityUtils.requireTeacherOrAdmin();
        accessGuard.requireManagedProblem(problemId);
        ProblemStatementVersion candidate = requireVersion(problemId, versionId);
        if (!STATUS_CANDIDATE.equals(candidate.getStatus())) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "只有待审候选版本可以丢弃");
        }
        versionMapper.update(null, new LambdaUpdateWrapper<ProblemStatementVersion>()
                .eq(ProblemStatementVersion::getId, versionId)
                .set(ProblemStatementVersion::getStatus, STATUS_DISCARDED)
                .set(ProblemStatementVersion::getUpdateTime, LocalDateTime.now()));
    }

    // ==================== worker 使用 ====================

    public ProblemStatementSnapshot snapshotOf(Long problemId, Long versionId) {
        return toSnapshot(requireVersion(problemId, versionId));
    }

    /**
     * 任务完成后生成候选版本。同一任务重跑时先清掉旧的候选，避免重试堆出多条。
     */
    public ProblemStatementVersion createCandidate(Long problemId, Long baseVersionId, Long jobId, Long actorId,
                                                   ProblemFormatApplicationService.Proposal proposal) {
        return transactionTemplate.execute(status -> {
            versionMapper.delete(new LambdaQueryWrapper<ProblemStatementVersion>()
                    .eq(ProblemStatementVersion::getProblemId, problemId)
                    .eq(ProblemStatementVersion::getJobId, jobId)
                    .eq(ProblemStatementVersion::getStatus, STATUS_CANDIDATE));
            ProblemStatementVersion version = new ProblemStatementVersion();
            version.setProblemId(problemId);
            version.setVersionNo(nextVersionNo(problemId));
            version.setStatus(STATUS_CANDIDATE);
            version.setSource(SOURCE_AI);
            fillFromSnapshot(version, proposal.formatted().asSnapshot());
            version.setBaseVersionId(baseVersionId);
            version.setJobId(jobId);
            version.setIssuesJson(writeIssues(proposal.issues()));
            version.setNote("AI 整理，待审阅");
            version.setCreatedBy(actorId);
            version.setCreateTime(LocalDateTime.now());
            version.setUpdateTime(LocalDateTime.now());
            versionMapper.insert(version);
            return version;
        });
    }

    // ==================== 内部 ====================

    public ProblemStatementVersion requireVersion(Long problemId, Long versionId) {
        if (versionId == null) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "版本 ID 不能为空");
        }
        ProblemStatementVersion version = versionMapper.selectById(versionId);
        if (version == null || !Objects.equals(version.getProblemId(), problemId)) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_NOT_FOUND, "题面版本不存在");
        }
        return version;
    }

    private Problem requireProblem(Long problemId) {
        Problem problem = problemService.getById(problemId);
        if (problem == null) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_NOT_FOUND, "题目不存在");
        }
        return problem;
    }

    private ProblemStatementVersion findLive(Long problemId) {
        return versionMapper.selectOne(new LambdaQueryWrapper<ProblemStatementVersion>()
                .eq(ProblemStatementVersion::getProblemId, problemId)
                .eq(ProblemStatementVersion::getStatus, STATUS_LIVE)
                .last("LIMIT 1"));
    }

    private void archiveLive(Long problemId) {
        versionMapper.update(null, new LambdaUpdateWrapper<ProblemStatementVersion>()
                .eq(ProblemStatementVersion::getProblemId, problemId)
                .eq(ProblemStatementVersion::getStatus, STATUS_LIVE)
                .set(ProblemStatementVersion::getStatus, STATUS_ARCHIVED)
                .set(ProblemStatementVersion::getUpdateTime, LocalDateTime.now()));
    }

    /** 版本号并发冲突由唯一索引兜底，冲突时重试。 */
    private int nextVersionNo(Long problemId) {
        ProblemStatementVersion latest = versionMapper.selectOne(
                new LambdaQueryWrapper<ProblemStatementVersion>()
                        .eq(ProblemStatementVersion::getProblemId, problemId)
                        .orderByDesc(ProblemStatementVersion::getVersionNo)
                        .last("LIMIT 1"));
        return latest == null ? 1 : latest.getVersionNo() + 1;
    }

    private List<String> normalizeFields(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return ALL_FIELDS;
        }
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        for (String field : requested) {
            if (field != null && ALL_FIELD_SET.contains(field.trim())) {
                fields.add(field.trim());
            }
        }
        if (fields.isEmpty()) {
            throw new BusinessException(ProblemErrorCode.PROBLEM_INPUT_INVALID, "没有可应用的字段");
        }
        return List.copyOf(fields);
    }

    private void writeSnapshotToProblem(Problem problem, ProblemStatementSnapshot snapshot, List<String> fields) {
        if (fields.contains("title")) {
            problem.setTitle(snapshot.title());
        }
        if (fields.contains("content")) {
            problem.setContent(snapshot.content());
        }
        if (fields.contains("inputDesc")) {
            problem.setInputDesc(snapshot.inputDesc());
        }
        if (fields.contains("outputDesc")) {
            problem.setOutputDesc(snapshot.outputDesc());
        }
        if (fields.contains("hint")) {
            problem.setHint(snapshot.hint());
        }
        if (fields.contains("timeLimit")) {
            problem.setTimeLimit(snapshot.timeLimitMs());
        }
        if (fields.contains("memoryLimit")) {
            problem.setMemoryLimit(snapshot.memoryLimitMb());
        }
        if (fields.contains("samples")) {
            formatApplicationService.replaceSamples(problem.getId(), snapshot.samples());
        }
    }

    private void fillFromSnapshot(ProblemStatementVersion version, ProblemStatementSnapshot snapshot) {
        version.setTitle(snapshot.title());
        version.setContent(snapshot.content());
        version.setInputDesc(snapshot.inputDesc());
        version.setOutputDesc(snapshot.outputDesc());
        version.setHint(snapshot.hint());
        version.setTimeLimit(snapshot.timeLimitMs());
        version.setMemoryLimit(snapshot.memoryLimitMb());
        version.setSamplesJson(writeSamples(snapshot.samples()));
    }

    public ProblemStatementSnapshot toSnapshot(ProblemStatementVersion version) {
        return new ProblemStatementSnapshot(
                nullToEmpty(version.getTitle()),
                nullToEmpty(version.getContent()),
                nullToEmpty(version.getInputDesc()),
                nullToEmpty(version.getOutputDesc()),
                nullToEmpty(version.getHint()),
                version.getTimeLimit() == null ? 1000 : version.getTimeLimit(),
                version.getMemoryLimit() == null ? 512 : version.getMemoryLimit(),
                readSamples(version.getSamplesJson()));
    }

    private ProblemFormattedContent contentOf(ProblemStatementVersion version) {
        return new ProblemFormattedContent(version.getTitle(), nullToEmpty(version.getContent()),
                nullToEmpty(version.getInputDesc()), nullToEmpty(version.getOutputDesc()),
                nullToEmpty(version.getHint()), version.getTimeLimit(), version.getMemoryLimit(),
                readSamples(version.getSamplesJson()));
    }

    private StatementVersionVO toVO(ProblemStatementVersion version, boolean live, boolean drifted) {
        List<FormatSample> samples = readSamples(version.getSamplesJson());
        List<StatementIssue> issues = readIssues(version.getIssuesJson());
        int errors = (int) issues.stream().filter(issue -> issue.severity() == IssueSeverity.ERROR).count();
        int warns = (int) issues.stream().filter(issue -> issue.severity() == IssueSeverity.WARN).count();
        return new StatementVersionVO(version.getId(), version.getProblemId(), version.getVersionNo(),
                version.getStatus(), version.getSource(), version.getTitle(), summarize(version.getContent()),
                version.getTimeLimit(), version.getMemoryLimit(), samples.size(),
                version.getBaseVersionId(), version.getJobId(), errors, warns, version.getCreatedBy(),
                version.getCreateTime(), live, drifted);
    }

    private String summarize(String content) {
        String text = nullToEmpty(content).replaceAll("[#*>`\\[\\]]", "").replaceAll("\\s+", " ").trim();
        return text.length() <= SUMMARY_LENGTH ? text : text.substring(0, SUMMARY_LENGTH) + "…";
    }

    private List<FormatSample> readSamples(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<FormatSample>>() {
            });
        } catch (Exception e) {
            log.warn("版本样例解析失败，按空样例处理");
            return List.of();
        }
    }

    private List<StatementIssue> readIssues(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<StatementIssue>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeSamples(List<FormatSample> samples) {
        try {
            return objectMapper.writeValueAsString(samples == null ? List.of() : samples);
        } catch (Exception e) {
            throw new IllegalStateException("样例序列化失败", e);
        }
    }

    private String writeIssues(List<StatementIssue> issues) {
        try {
            return objectMapper.writeValueAsString(issues == null ? List.of() : issues);
        } catch (Exception e) {
            throw new IllegalStateException("质量结果序列化失败", e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
