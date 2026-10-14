package top.everblue.oj.analytics.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.everblue.oj.analytics.domain.StudentProfile;
import top.everblue.oj.analytics.domain.TagMastery;
import top.everblue.oj.analytics.entity.ProblemTag;
import top.everblue.oj.analytics.entity.StudentProfileSnapshot;
import top.everblue.oj.analytics.mapper.ProblemTagMapper;
import top.everblue.oj.analytics.mapper.ProfileSnapshotMapper;
import top.everblue.oj.analytics.report.AiAdviceBuilder;
import top.everblue.oj.analytics.report.FreemarkerHtmlBuilder;
import top.everblue.oj.analytics.report.RecommendedProblem;
import top.everblue.oj.analytics.report.ReportRenderException;
import top.everblue.oj.analytics.report.ReportRenderer;
import top.everblue.oj.analytics.report.ReportResult;
import top.everblue.oj.api.problem.ProblemDigest;
import top.everblue.oj.api.problem.ProblemQuery;
import top.everblue.oj.api.profile.ProfileQuery;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.common.storage.FileService;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 家长周报应用服务——编排画像构建、AI 建议、HTML/PDF 渲染、COS 上传与快照落库。
 * <p>
 * 降级链（任何一环失败都不阻塞报告）：
 * <pre>
 *   AI 建议失败        → 本地模板文案（AiAdviceBuilder 内部处理）
 *   Chromium 不可达    → 返回 HTML 供前端预览，pdfUrl=null
 *   COS 未配置/失败    → PDF 丢弃，pdfUrl=null，快照仍保留
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportApplicationService {

    private static final String COS_REPORT_DIR = "weekly-reports";
    private static final int MAX_RECOMMENDATIONS = 5;
    private static final int MAX_WEAK_TAGS_FOR_RECOMMEND = 3;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProfileApplicationService profileApplicationService;
    private final ProfileQuery profileQuery;
    private final ProblemQuery problemQuery;
    private final ProblemTagMapper problemTagMapper;
    private final ProfileSnapshotMapper profileSnapshotMapper;
    private final FreemarkerHtmlBuilder htmlBuilder;
    private final AiAdviceBuilder aiAdviceBuilder;
    private final ReportRenderer reportRenderer;
    private final FileService fileService;

    /**
     * 生成指定学生的周报（HTTP 入口，需教师/管理员权限）。
     *
     * @param periodStart 周期开始；null 则默认上周一
     * @param periodEnd   周期结束；null 则默认上周日
     */
    public ReportResult generateWeeklyReport(Long studentId, Long classId,
                                             LocalDate periodStart, LocalDate periodEnd) {
        SecurityUtils.requireTeacherOrAdmin();
        return doGenerate(studentId, classId, periodStart, periodEnd, false);
    }

    /**
     * 系统内部生成周报（定时任务用，无登录上下文，跳过权限校验）。
     * 仅限可信调用方（如 WeeklyProfileReportTask）。
     */
    public ReportResult generateWeeklyReportInternal(Long studentId, Long classId,
                                                     LocalDate periodStart, LocalDate periodEnd) {
        return doGenerate(studentId, classId, periodStart, periodEnd, true);
    }

    /**
     * 仅生成周报 HTML（跳过 PDF 渲染和 COS 上传），供前端预览。
     * 需要教师/管理员权限。
     */
    public String generateWeeklyReportHtml(Long studentId, Long classId,
                                           LocalDate periodStart, LocalDate periodEnd) {
        SecurityUtils.requireTeacherOrAdmin();
        return doGenerateHtml(studentId, classId, periodStart, periodEnd);
    }

    /**
     * 内部生成周报 HTML（无权限校验），供定时任务和批量接口复用。
     */
    public String generateWeeklyReportHtmlInternal(Long studentId, Long classId,
                                                    LocalDate periodStart, LocalDate periodEnd) {
        return doGenerateHtml(studentId, classId, periodStart, periodEnd);
    }

    private String doGenerateHtml(Long studentId, Long classId,
                                   LocalDate periodStart, LocalDate periodEnd) {
        LocalDate[] period = resolvePeriod(periodStart, periodEnd);
        StudentProfile profile = profileApplicationService.buildForReportInternal(
                studentId, classId, period[0], period[1]);
        String advice = aiAdviceBuilder.buildAdvice(profile);
        List<RecommendedProblem> recommendations = buildRecommendations(profile);

        // 查上周快照，提取维度掌握度用于对比条状图
        Map<String, Integer> lastWeekMasteries = loadLastWeekMasteries(
                studentId, period[0]);

        return htmlBuilder.buildHtml(profile, advice, recommendations, lastWeekMasteries);
    }

    /**
     * 查询学生上周画像快照，提取各维度掌握度映射。
     * 用于对比条状图"本周 vs 上周"。
     */
    private Map<String, Integer> loadLastWeekMasteries(
            Long studentId, LocalDate thisWeekStart) {
        try {
            LocalDate lastWeekStart = thisWeekStart.minusDays(7);
            StudentProfileSnapshot lastSnapshot = profileSnapshotMapper.selectOne(
                    new LambdaQueryWrapper<StudentProfileSnapshot>()
                            .eq(StudentProfileSnapshot::getStudentId, studentId)
                            .eq(StudentProfileSnapshot::getPeriodStart, lastWeekStart));
            if (lastSnapshot == null || lastSnapshot.getSnapshotJson() == null) {
                return Map.of();
            }
            // 从 snapshot_json 反序列化上周画像，只提取 dimensionMasteries
            JsonNode root = JSON.readTree(lastSnapshot.getSnapshotJson());
            JsonNode dimsNode = root.get("dimensionMasteries");
            if (dimsNode == null || !dimsNode.isObject()) {
                return Map.of();
            }
            Map<String, Integer> result = new HashMap<>();
            dimsNode.fields().forEachRemaining(entry -> {
                JsonNode val = entry.getValue();
                if (val != null && val.has("mastery")) {
                    result.put(entry.getKey(), val.get("mastery").asInt());
                }
            });
            return result;
        } catch (Exception e) {
            log.warn("查询上周快照失败 studentId={}", studentId, e);
            return Map.of();
        }
    }

    private ReportResult doGenerate(Long studentId, Long classId,
                                    LocalDate periodStart, LocalDate periodEnd,
                                    boolean internal) {
        // 1. 周期：默认上周（周一～周日）
        LocalDate[] period = resolvePeriod(periodStart, periodEnd);

        // 2. 构建画像（教师视角，不走个人缓存；内部调用跳过权限校验）
        StudentProfile profile = internal
                ? profileApplicationService.buildForReportInternal(studentId, classId, period[0], period[1])
                : profileApplicationService.buildForReport(studentId, classId, period[0], period[1]);

        // 3. 快照幂等落库（先落数据，report_url 后面补）
        StudentProfileSnapshot snapshot = profileApplicationService.saveSnapshot(profile);

        // 4. AI 建议（内部自带模板降级）
        String advice = aiAdviceBuilder.buildAdvice(profile);

        // 5. 推荐题单（排除已 AC 的题）
        List<RecommendedProblem> recommendations = buildRecommendations(profile);

        // 6. 渲染 HTML。PDF 与 HTML 预览使用同一份上周基线，保证能力变化图一致。
        Map<String, Integer> lastWeekMasteries = loadLastWeekMasteries(studentId, period[0]);
        String html = htmlBuilder.buildHtml(profile, advice, recommendations, lastWeekMasteries);

        // 7. HTML → PDF → COS（两段降级）
        String pdfUrl = tryRenderAndUpload(html, profile, snapshot);

        if (pdfUrl != null) {
            return new ReportResult(snapshot.getId(), pdfUrl, true, null);
        }
        // 降级：返回 HTML，前端可直接预览或转链
        return new ReportResult(snapshot.getId(), null, false, html);
    }

    // ------------------------------------------------------------------
    // 推荐题单：薄弱 tag → problem_tag → 排除已 AC → 难度升序取前 N
    // ------------------------------------------------------------------

    private List<RecommendedProblem> buildRecommendations(StudentProfile profile) {
        List<TagMastery> weakPoints = profile.weakPoints();
        if (weakPoints == null || weakPoints.isEmpty()) {
            return List.of();
        }

        List<Long> weakTagIds = weakPoints.stream()
                .limit(MAX_WEAK_TAGS_FOR_RECOMMEND)
                .map(TagMastery::tagId)
                .toList();
        Map<Long, String> tagNameById = weakPoints.stream()
                .collect(Collectors.toMap(TagMastery::tagId, TagMastery::tagName));

        // 薄弱 tag 下的全部题目
        List<ProblemTag> relations = problemTagMapper.selectList(
                new LambdaQueryWrapper<ProblemTag>().in(ProblemTag::getTagId, weakTagIds));
        if (relations.isEmpty()) {
            return List.of();
        }

        // 排除学生已 AC 的题，避免推荐已掌握内容
        Set<Long> solved = new HashSet<>(profileQuery.findSolvedProblemIds(profile.studentId()));

        List<Long> candidateIds = relations.stream()
                .map(ProblemTag::getProblemId)
                .distinct()
                .filter(pid -> !solved.contains(pid))
                .toList();
        if (candidateIds.isEmpty()) {
            return List.of();
        }

        // problemId → 其薄弱 tag（一个题可能挂多个薄弱 tag，取第一个用于展示）
        Map<Long, Long> tagByProblem = relations.stream()
                .collect(Collectors.toMap(ProblemTag::getProblemId, ProblemTag::getTagId, (a, b) -> a));

        return problemQuery.findDigestBatch(candidateIds).stream()
                .filter(d -> Boolean.TRUE.equals(d.isPublic()))
                .sorted(Comparator.comparingInt(d -> d.difficulty() == null ? 0 : d.difficulty()))
                .limit(MAX_RECOMMENDATIONS)
                .map(d -> new RecommendedProblem(
                        tagNameById.getOrDefault(tagByProblem.get(d.id()), "综合"),
                        d.title(),
                        String.valueOf(d.difficulty() == null ? 800 : d.difficulty())))
                .toList();
    }

    // ------------------------------------------------------------------
    // PDF 渲染 + 上传（两段降级）
    // ------------------------------------------------------------------

    private String tryRenderAndUpload(String html, StudentProfile profile,
                                      StudentProfileSnapshot snapshot) {
        byte[] pdf;
        try {
            pdf = reportRenderer.renderPdf(html);
        } catch (ReportRenderException e) {
            log.warn("PDF 渲染失败，降级返回 HTML。studentId={}: {}",
                    profile.studentId(), e.getMessage());
            return null;
        }

        if (!fileService.isCosConfigured()) {
            log.warn("COS 未配置，PDF 已生成但不上传。studentId={}", profile.studentId());
            return null;
        }

        try {
            String url = fileService.uploadBytes(pdf, COS_REPORT_DIR, ".pdf");
            // 回填快照的 report_url
            profileSnapshotMapper.updateReportUrl(
                    profile.studentId(), profile.periodStart(), url);
            snapshot.setReportUrl(url);
            return url;
        } catch (Exception e) {
            log.error("PDF 上传 COS 失败 studentId={}", profile.studentId(), e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 周期与标签工具
    // ------------------------------------------------------------------

    /** null 时默认上周一～上周日 */
    private LocalDate[] resolvePeriod(LocalDate periodStart, LocalDate periodEnd) {
        if (periodStart != null && periodEnd != null) {
            return new LocalDate[]{periodStart, periodEnd};
        }
        LocalDate lastMonday = LocalDate.now()
                .with(DayOfWeek.MONDAY)
                .minusWeeks(1);
        return new LocalDate[]{lastMonday, lastMonday.plusDays(6)};
    }
}
