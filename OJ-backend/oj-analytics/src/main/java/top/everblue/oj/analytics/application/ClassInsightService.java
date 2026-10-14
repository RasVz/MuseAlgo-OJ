package top.everblue.oj.analytics.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.ai.AiAssistant;
import top.everblue.oj.api.insight.ClassInsightQuery;
import top.everblue.oj.api.teaching.ClassQuery;
import top.everblue.oj.common.exception.ForbiddenException;
import top.everblue.oj.common.utils.SecurityUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 班级学情聚合（比赛管理右侧面板数据源）。
 * <p>
 * 以 {@code class_student} 归属关系把 submission 聚合成班级视角的统计：
 * 班级概览、每日提交/AC 趋势、题目通过率、学生活跃分层；
 * AI 解读把精简后的统计 JSON 喂给 {@code teacher-class-insight} 提示词。
 * <p>
 * 跨模块数据全部走 oj-api 契约（{@link ClassInsightQuery}、{@link ClassQuery}），
 * 不直接注入 SubmissionMapper / ClassService。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClassInsightService {

    /** 活跃学生阈值：周期内提交次数 ≥ 该值视为活跃 */
    private static final long ACTIVE_SUBMIT_THRESHOLD = 5;
    /** 喂给 AI 的题目数上限（按提交量取前 N） */
    private static final int AI_PROBLEM_LIMIT = 15;
    /** 喂给 AI 的头部/尾部学生数 */
    private static final int AI_STUDENT_LIMIT = 5;
    /** days 参数上限，防止误传超大窗口 */
    private static final int MAX_DAYS = 365;

    private final ClassQuery classQuery;
    private final ClassInsightQuery classInsightQuery;
    private final AiAssistant aiAssistant;

    /**
     * 聚合班级近 N 天学情。权限：班主任本人或管理员。
     */
    public Map<String, Object> aggregate(Long classId, int days) {
        ClassQuery.ClassInfo classInfo = classQuery.findById(classId).orElse(null);
        if (classInfo == null) {
            throw new ForbiddenException("班级不存在");
        }
        Long currentUserId = SecurityUtils.getUserId();
        if (currentUserId == null
                || (!currentUserId.equals(classInfo.teacherId()) && !SecurityUtils.isAdmin())) {
            throw new ForbiddenException("无权限查看该班级学情");
        }

        int safeDays = Math.max(1, Math.min(days, MAX_DAYS));
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(safeDays);

        ClassInsightQuery.ClassSummary summary = classInsightQuery.summary(classId, from, to);
        List<ClassInsightQuery.DailyStat> daily = classInsightQuery.daily(classId, from, to);
        List<ClassInsightQuery.ProblemStat> problems = classInsightQuery.problems(classId, from, to);
        List<ClassInsightQuery.StudentStat> students = classInsightQuery.students(classId, from, to);

        long memberCount = classQuery.countStudents(classId);
        long totalSubmissions = summary.totalSubmissions();
        long acceptedCount = summary.acceptedCount();
        long activeStudentCount = summary.activeStudentCount();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("classId", classId);
        result.put("className", classInfo.name());
        result.put("days", safeDays);
        result.put("periodStart", from.toLocalDate().toString());
        result.put("periodEnd", to.toLocalDate().toString());
        result.put("memberCount", memberCount);

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalSubmissions", totalSubmissions);
        overview.put("acceptedCount", acceptedCount);
        overview.put("acRate", percent(acceptedCount, totalSubmissions));
        overview.put("activeStudentCount", activeStudentCount);
        overview.put("avgSubmissionsPerStudent", round1(totalSubmissions * 1.0 / Math.max(memberCount, 1)));
        overview.put("avgDailySubmissions", round1(totalSubmissions * 1.0 / safeDays));
        result.put("overview", overview);

        result.put("daily", fillDaily(daily, from.toLocalDate(), to.toLocalDate()));

        List<Map<String, Object>> problemVos = new ArrayList<>();
        for (ClassInsightQuery.ProblemStat row : problems) {
            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("problemId", row.problemId());
            vo.put("problemTitle", row.problemTitle() == null ? "未知题目" : row.problemTitle());
            long submit = row.submitCount();
            long ac = row.acCount();
            vo.put("submitCount", submit);
            vo.put("acCount", ac);
            vo.put("passRate", percent(ac, submit));
            problemVos.add(vo);
        }
        result.put("problems", problemVos);

        // 学生维度 + 活跃分层：活跃(≥5 次) / 一般(1~4 次) / 沉寂(0 次)
        long activeTier = 0;
        long ordinaryTier = 0;
        List<Map<String, Object>> studentVos = new ArrayList<>();
        for (ClassInsightQuery.StudentStat row : students) {
            long submit = row.submitCount();
            if (submit >= ACTIVE_SUBMIT_THRESHOLD) {
                activeTier++;
            } else if (submit >= 1) {
                ordinaryTier++;
            }
            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("userId", row.userId());
            vo.put("studentName", row.studentName() == null ? "未知学生" : row.studentName());
            vo.put("submitCount", submit);
            vo.put("acCount", row.acCount());
            vo.put("lastSubmitTime", row.lastSubmitTime() == null ? null : String.valueOf(row.lastSubmitTime()));
            studentVos.add(vo);
        }
        long silentTier = Math.max(0, memberCount - activeTier - ordinaryTier);

        Map<String, Object> tiers = new LinkedHashMap<>();
        tiers.put("active", activeTier);
        tiers.put("ordinary", ordinaryTier);
        tiers.put("silent", silentTier);
        tiers.put("activeRatio", percent(activeTier, memberCount));
        result.put("studentTiers", tiers);
        result.put("students", studentVos);

        return result;
    }

    /**
     * 生成 AI 学情解读：聚合 → 精简 JSON → teacher-class-insight 提示词。
     */
    public String generateAiInsight(Long classId, int days) {
        Map<String, Object> stats = aggregate(classId, days);
        return aiAssistant.chatWithTool("teacher-class-insight", buildAiJson(stats));
    }

    /** 只把 AI 需要的关键字段拼成 JSON，控制 token 量 */
    private String buildAiJson(Map<String, Object> stats) {
        Map<String, Object> brief = new LinkedHashMap<>();
        brief.put("className", stats.get("className"));
        brief.put("days", stats.get("days"));
        brief.put("periodStart", stats.get("periodStart"));
        brief.put("periodEnd", stats.get("periodEnd"));
        brief.put("memberCount", stats.get("memberCount"));
        brief.put("overview", stats.get("overview"));
        brief.put("studentTiers", stats.get("studentTiers"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> problems = (List<Map<String, Object>>) stats.get("problems");
        brief.put("problems", problems.subList(0, Math.min(AI_PROBLEM_LIMIT, problems.size())));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> students = (List<Map<String, Object>>) stats.get("students");
        int n = students.size();
        brief.put("topStudents", students.subList(0, Math.min(AI_STUDENT_LIMIT, n)));
        brief.put("bottomStudents", students.subList(Math.max(0, n - AI_STUDENT_LIMIT), n));

        return cn.hutool.json.JSONUtil.toJsonStr(brief);
    }

    /** 按天补零，保证图表 X 轴连续 */
    private List<Map<String, Object>> fillDaily(List<ClassInsightQuery.DailyStat> rows, LocalDate from, LocalDate to) {
        Map<String, Map<String, Object>> byDate = new LinkedHashMap<>();
        for (ClassInsightQuery.DailyStat row : rows) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("submitDate", row.submitDate());
            normalized.put("submitCount", row.submitCount());
            normalized.put("acCount", row.acCount());
            byDate.put(row.submitDate(), normalized);
        }
        List<Map<String, Object>> filled = new ArrayList<>();
        LocalDate cursor = from;
        while (!cursor.isAfter(to)) {
            String key = cursor.toString();
            if (byDate.containsKey(key)) {
                filled.add(byDate.get(key));
            } else {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("submitDate", key);
                empty.put("submitCount", 0L);
                empty.put("acCount", 0L);
                filled.add(empty);
            }
            cursor = cursor.plusDays(1);
        }
        return filled;
    }

    /** 百分比，保留 1 位小数 */
    private double percent(long part, long total) {
        return total <= 0 ? 0.0 : Math.round(part * 1000.0 / total) / 10.0;
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
