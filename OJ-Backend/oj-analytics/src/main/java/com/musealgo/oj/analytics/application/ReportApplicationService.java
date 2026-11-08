package com.musealgo.oj.analytics.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.musealgo.oj.analytics.domain.StudentProfile;
import com.musealgo.oj.analytics.domain.TagMastery;
import com.musealgo.oj.analytics.entity.ProblemTag;
import com.musealgo.oj.analytics.entity.StudentProfileSnapshot;
import com.musealgo.oj.analytics.mapper.ProblemTagMapper;
import com.musealgo.oj.analytics.mapper.ProfileSnapshotMapper;
import com.musealgo.oj.analytics.report.AiAdviceBuilder;
import com.musealgo.oj.analytics.report.FreemarkerHtmlBuilder;
import com.musealgo.oj.analytics.report.RecommendedProblem;
import com.musealgo.oj.api.problem.ProblemDigest;
import com.musealgo.oj.api.problem.ProblemQuery;
import com.musealgo.oj.api.profile.ProfileQuery;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 家长周报应用服务：画像、建议、受权 HTML 与快照落库。
 * <p>
 * HTML 是权威产物，PDF 通过浏览器打印另存；禁止经公共文件服务发布报告。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportApplicationService {

    private static final int MAX_RECOMMENDATIONS = 5;
    private static final int MAX_WEAK_TAGS_FOR_RECOMMEND = 3;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProfileApplicationService profileApplicationService;
    private final ProfileQuery profileQuery;
    private final ProblemQuery problemQuery;
    private final ProblemTagMapper problemTagMapper;
    private final ProfileSnapshotMapper profileSnapshotMapper;
    private final FreemarkerHtmlBuilder htmlBuilder;
    private final AiAdviceBuilder aiAdviceBuilder;

    /**
     * Build report input and HTML without opening a report finalization transaction or writing a
     * profile snapshot. The worker calls this only after its claim transaction has committed.
     */
    public PreparedReport prepareWeeklyReportInternal(Long studentId, Long classId,
                                                       LocalDate periodStart, LocalDate periodEnd) {
        LocalDate[] period = resolvePeriod(periodStart, periodEnd);
        StudentProfile profile = profileApplicationService.buildForReportInternal(
                studentId, classId, period[0], period[1]);
        String advice = aiAdviceBuilder.buildAdvice(profile);
        List<RecommendedProblem> recommendations = buildRecommendations(profile);
        Map<String, Integer> lastWeekMasteries = loadLastWeekMasteries(studentId, period[0]);
        String html = htmlBuilder.buildHtml(profile, advice, recommendations, lastWeekMasteries);
        return new PreparedReport(profile, html);
    }

    public record PreparedReport(StudentProfile profile, String html) {
    }

    /**
     * 查询学生上周画像快照，提取各维度掌握度映射。
     * 用于对比条状图"本周 vs 上周"。
     */
    private Map<String, Integer> loadLastWeekMasteries(
            Long studentId, LocalDate thisWeekStart) {
        try {
            LocalDate lastWeekStart = thisWeekStart.minusDays(7);
            // stale=0：重判后该周快照的派生值已过期（T8 只标脏、不重建），
            // 不能拿来当「本周 vs 上周」的对比基线；查询不到时下方按无基线降级为 Map.of()。
            StudentProfileSnapshot lastSnapshot = profileSnapshotMapper.selectOne(
                    new LambdaQueryWrapper<StudentProfileSnapshot>()
                            .eq(StudentProfileSnapshot::getStudentId, studentId)
                            .eq(StudentProfileSnapshot::getPeriodStart, lastWeekStart)
                            .eq(StudentProfileSnapshot::getStale, false));
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
                        difficultyLabel(d.difficulty())))
                .toList();
    }

    // ------------------------------------------------------------------
    // 周期与标签工具
    // ------------------------------------------------------------------

    /**
     * 把 800-3000 的难度分数转成家长能读懂的档位文案。
     * <p>
     * 报告是独立 HTML（家长端不经过前端工程），所以这里必须自带一份档位映射；
     * 档位名称与前端题目列表保持一致（见 {@code OJ-Frontend/src/utils/difficulty.js}），
     * 但按连续区间分档，避免分数落在前端 100 分步长空隙时显示「未评定」。
     * </p>
     */
    static String difficultyLabel(Integer difficulty) {
        if (difficulty == null || difficulty < 800) return "未评定";
        int score = difficulty;
        if (score < 1000) return "入门";
        if (score < 1200) return "入门+";
        if (score < 1400) return "普及-";
        if (score < 1600) return "普及";
        if (score < 1800) return "普及+";
        if (score < 2000) return "提高-";
        if (score < 2200) return "提高";
        if (score < 2400) return "提高+";
        if (score < 2600) return "省选-";
        if (score < 2800) return "省选 / NOI-";
        return "NOI 及以上";
    }

    /** null 时默认上周一～上周日 */
    private LocalDate[] resolvePeriod(LocalDate periodStart, LocalDate periodEnd) {
        if (periodStart != null && periodEnd != null) {
            return new LocalDate[]{periodStart, periodEnd};
        }
        LocalDate lastMonday = LocalDate.now(BUSINESS_ZONE)
                .with(DayOfWeek.MONDAY)
                .minusWeeks(1);
        return new LocalDate[]{lastMonday, lastMonday.plusDays(6)};
    }
}
