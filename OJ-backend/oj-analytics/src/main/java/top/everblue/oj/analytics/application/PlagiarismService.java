package top.everblue.oj.analytics.application;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import top.everblue.oj.analytics.domain.CodeFingerprinter;
import top.everblue.oj.analytics.domain.CodeFingerprinter.Fingerprint;
import top.everblue.oj.analytics.dto.PlagiarismReportVO;
import top.everblue.oj.analytics.entity.PlagiarismReport;
import top.everblue.oj.analytics.mapper.PlagiarismReportMapper;
import top.everblue.oj.api.submission.SubmissionCodeQuery;
import top.everblue.oj.api.submission.SubmissionCodeQuery.CodePairSource;
import top.everblue.oj.api.teaching.ClassQuery;
import top.everblue.oj.api.contest.ContestSchedule;
import top.everblue.oj.api.contest.ContestScheduleQuery;
import top.everblue.oj.common.exception.ForbiddenException;
import top.everblue.oj.common.utils.SecurityUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 代码查重应用服务（M1：班级内同题两两比对；M2：比赛维度跨班比对 + 赛后自动触发）。
 * <p>
 * 数据经 {@link SubmissionCodeQuery} 契约读取（oj-judge 实现），班级归属经
 * {@link ClassQuery} 校验、比赛归属经 {@link ContestScheduleQuery} 校验；
 * 指纹引擎在 {@link CodeFingerprinter}（AST 优先，native 不可用自动回退 TOKEN，
 * M2 起带指纹缓存）。入库前 user_a_id &lt; user_b_id 规范化，
 * 唯一键 uk_submission_pair 冲突（重复执行）时幂等跳过。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlagiarismService {

    /** 疑似阈值：相似度 ≥ 0.85 标记疑似（PENDING） */
    public static final double SUSPICIOUS_THRESHOLD = 0.85;
    /** 关注阈值：相似度 ≥ 0.70 入库关注 */
    public static final double WATCH_THRESHOLD = 0.70;
    /** 忽略过短代码（空提交/占位代码），避免无效比对 */
    private static final int MIN_CODE_LENGTH = 20;

    private static final String STATUS_PENDING = "PENDING";
    /** 报告处置状态白名单（恢复待核实也允许，处置可逆） */
    private static final java.util.Set<String> STATUS_ALLOWED =
            java.util.Set.of(STATUS_PENDING, "REVIEWED", "IGNORED");

    private final SubmissionCodeQuery submissionCodeQuery;
    private final ClassQuery classQuery;
    private final ContestScheduleQuery contestScheduleQuery;
    private final PlagiarismReportMapper reportMapper;

    /**
     * 触发查重：班级内同题提交两两比对，相似度 ≥ 关注阈值入库。
     * 同步执行，返回本次新增报告数（重复对幂等跳过不计）。
     *
     * @param classId   班级 ID（班主任本人或管理员）
     * @param problemId 题目 ID
     * @return 新增报告数
     */
    public int runPlagiarismCheck(Long classId, Long problemId) {
        requireClassPermission(classId);
        List<CodePairSource> sources = submissionCodeQuery.findByClassAndProblem(classId, problemId, null);
        return compareAndStore(sources, classId, null, problemId);
    }

    /**
     * 触发查重（比赛维度，M2）：同场比赛全部提交同题两两比对（跨班）。
     * 管理员或比赛创建者可调用。
     *
     * @param contestId 比赛 ID
     * @return 新增报告数
     */
    public int runContestPlagiarismCheck(Long contestId) {
        requireContestPermission(contestId);
        return runContestPlagiarismCheckInternal(contestId);
    }

    /**
     * 比赛维度查重内部实现（赛后自动触发调度器用，不校验用户权限）。
     */
    public int runContestPlagiarismCheckInternal(Long contestId) {
        List<Long> problemIds = submissionCodeQuery.findContestProblemIds(contestId);
        if (problemIds.isEmpty()) {
            log.info("查重跳过：比赛 {} 无有效提交", contestId);
            return 0;
        }
        int inserted = 0;
        for (Long problemId : problemIds) {
            List<CodePairSource> sources =
                    submissionCodeQuery.findByContestAndProblem(contestId, problemId, null);
            inserted += compareAndStore(sources, null, contestId, problemId);
        }
        log.info("比赛查重完成：比赛 {}，题目 {} 道，新增报告 {}", contestId, problemIds.size(), inserted);
        return inserted;
    }

    /** 共享比对入库逻辑（班级/比赛维度通用，幂等跳过重复对） */
    private int compareAndStore(List<CodePairSource> sources, Long classId, Long contestId, Long problemId) {
        List<CodePairSource> valid = sources.stream()
                .filter(s -> s.code() != null && s.code().trim().length() >= MIN_CODE_LENGTH)
                .toList();
        if (valid.size() < 2) {
            log.info("查重跳过：{} 题目 {} 有效提交不足 2 份（{}）",
                    contestId != null ? "比赛 " + contestId : "班级 " + classId,
                    problemId, valid.size());
            return 0;
        }

        int inserted = 0;
        int skipped = 0;
        for (int i = 0; i < valid.size() - 1; i++) {
            CodePairSource a = valid.get(i);
            Fingerprint fa = CodeFingerprinter.fingerprint(a.code());
            for (int j = i + 1; j < valid.size(); j++) {
                CodePairSource b = valid.get(j);
                Fingerprint fb = CodeFingerprinter.fingerprint(b.code());
                double similarity = CodeFingerprinter.similarity(fa, fb);
                if (similarity < WATCH_THRESHOLD) {
                    continue;
                }
                String method = CodeFingerprinter.METHOD_TOKEN.equals(fa.method())
                        || CodeFingerprinter.METHOD_TOKEN.equals(fb.method())
                        ? CodeFingerprinter.METHOD_TOKEN : CodeFingerprinter.METHOD_AST;
                PlagiarismReport report = buildReport(classId, contestId, problemId, a, b, similarity, method);
                try {
                    reportMapper.insert(report);
                    inserted++;
                } catch (DuplicateKeyException e) {
                    // 幂等：uk_submission_pair 冲突说明该对已比对过，跳过
                    skipped++;
                }
            }
        }
        log.info("查重完成：{} 题目 {}，提交 {} 份，新增报告 {}，跳过重复 {}",
                contestId != null ? "比赛 " + contestId : "班级 " + classId,
                problemId, valid.size(), inserted, skipped);
        return inserted;
    }

    /**
     * 查重报告分页（相似度降序）。classId/contestId 为空时仅要求教师/管理员登录。
     *
     * @return 报告分页（含双方用户展示名）
     */
    public IPage<PlagiarismReportVO> pageReports(Long classId, Long contestId, Long problemId, String status,
                                                 long page, long size) {
        SecurityUtils.requireTeacherOrAdmin();
        if (classId != null) {
            requireClassPermission(classId);
        }
        Page<Map<String, Object>> pageParam = new Page<>(Math.max(page, 1), Math.max(size, 1));
        IPage<Map<String, Object>> raw = reportMapper.selectReportPage(
                pageParam, classId, contestId, problemId, status);
        return raw.convert(PlagiarismService::toVo);
    }

    /**
     * 取提交代码（报告展开对比用），教师/管理员可看。
     */
    public List<CodePairSource> findCodes(Collection<Long> submissionIds) {
        SecurityUtils.requireTeacherOrAdmin();
        return submissionCodeQuery.findBySubmissionIds(submissionIds);
    }

    /**
     * 更新报告处置状态（已核实 / 已忽略 / 恢复待核实）。
     * <p>
     * 权限与查重触发一致：教师/管理员；班级报告按班主任归属校验，
     * 比赛报告按创建者归属校验；处置可逆（允许改回 PENDING）。
     * </p>
     *
     * @param id     报告 ID
     * @param status 目标状态（PENDING / REVIEWED / IGNORED）
     */
    public void updateReportStatus(Long id, String status) {
        SecurityUtils.requireTeacherOrAdmin();
        if (!STATUS_ALLOWED.contains(status)) {
            throw new IllegalArgumentException("非法的报告状态: " + status);
        }
        PlagiarismReport report = reportMapper.selectById(id);
        if (report == null) {
            throw new IllegalArgumentException("报告不存在");
        }
        if (report.getClassId() != null) {
            requireClassPermission(report.getClassId());
        } else if (report.getContestId() != null) {
            requireContestPermission(report.getContestId());
        }
        PlagiarismReport update = new PlagiarismReport();
        update.setId(id);
        update.setStatus(status);
        reportMapper.updateById(update);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 班主任本人或管理员可操作该班级 */
    private void requireClassPermission(Long classId) {
        SecurityUtils.requireTeacherOrAdmin();
        ClassQuery.ClassInfo classInfo = classQuery.findById(classId).orElse(null);
        if (classInfo == null) {
            throw new ForbiddenException("班级不存在");
        }
        Long currentUserId = SecurityUtils.getUserId();
        if (currentUserId == null
                || (!currentUserId.equals(classInfo.teacherId()) && !SecurityUtils.isAdmin())) {
            throw new ForbiddenException("无权限操作该班级");
        }
    }

    /** 比赛创建者或管理员可操作该比赛 */
    private void requireContestPermission(Long contestId) {
        SecurityUtils.requireTeacherOrAdmin();
        ContestSchedule schedule = contestScheduleQuery.findById(contestId).orElse(null);
        if (schedule == null) {
            throw new ForbiddenException("比赛不存在");
        }
        Long currentUserId = SecurityUtils.getUserId();
        if (currentUserId == null
                || (!currentUserId.equals(schedule.authorId()) && !SecurityUtils.isAdmin())) {
            throw new ForbiddenException("无权限操作该比赛");
        }
    }

    /** 构造报告，user_a_id &lt; user_b_id 规范化避免重复对 */
    private PlagiarismReport buildReport(Long classId, Long contestId, Long problemId,
                                         CodePairSource a, CodePairSource b,
                                         double similarity, String method) {
        CodePairSource first = a.userId() <= b.userId() ? a : b;
        CodePairSource second = a.userId() <= b.userId() ? b : a;
        PlagiarismReport report = new PlagiarismReport();
        report.setClassId(classId);
        report.setContestId(contestId);
        report.setProblemId(problemId);
        report.setSubmissionAId(first.submissionId());
        report.setSubmissionBId(second.submissionId());
        report.setUserAId(first.userId());
        report.setUserBId(second.userId());
        report.setSimilarity(Math.round(similarity * 1000.0) / 1000.0);
        report.setMethod(method);
        report.setStatus(STATUS_PENDING);
        report.setCreatedAt(LocalDateTime.now());
        return report;
    }

    private static PlagiarismReportVO toVo(Map<String, Object> row) {
        return new PlagiarismReportVO(
                ((Number) row.get("id")).longValue(),
                row.get("classId") != null ? ((Number) row.get("classId")).longValue() : null,
                row.get("contestId") != null ? ((Number) row.get("contestId")).longValue() : null,
                ((Number) row.get("problemId")).longValue(),
                ((Number) row.get("submissionAId")).longValue(),
                ((Number) row.get("submissionBId")).longValue(),
                ((Number) row.get("userAId")).longValue(),
                ((Number) row.get("userBId")).longValue(),
                (String) row.get("userAName"),
                (String) row.get("userBName"),
                ((Number) row.get("similarity")).doubleValue(),
                (String) row.get("method"),
                (String) row.get("status"),
                (LocalDateTime) row.get("createdAt"));
    }
}
