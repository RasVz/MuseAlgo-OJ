package top.everblue.oj.api.insight;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 班级学情聚合查询契约 — 跨模块读取班级维度提交统计的唯一入口。
 * <p>
 * 实现由 oj-judge 模块提供（数据源是 submission 表，SQL 在 SubmissionMapper 中），
 * oj-analytics 通过此契约消费数据，不直接注入 SubmissionMapper。
 * </p>
 */
public interface ClassInsightQuery {

    /** 班级概览：提交总数 / AC 数 / 活跃学生数 / 涉及题目数。 */
    ClassSummary summary(Long classId, LocalDateTime from, LocalDateTime to);

    /** 每日提交/AC 趋势（按天聚合）。 */
    List<DailyStat> daily(Long classId, LocalDateTime from, LocalDateTime to);

    /** 题目维度统计（提交量降序，含通过率分子分母）。 */
    List<ProblemStat> problems(Long classId, LocalDateTime from, LocalDateTime to);

    /** 学生维度统计（提交量降序，含活跃时间）。 */
    List<StudentStat> students(Long classId, LocalDateTime from, LocalDateTime to);

    /** 班级概览。 */
    record ClassSummary(long totalSubmissions, long acceptedCount, long activeStudentCount, long problemCount) {
    }

    /** 每日统计。 */
    record DailyStat(String submitDate, long submitCount, long acCount) {
    }

    /** 题目统计。 */
    record ProblemStat(Long problemId, String problemTitle, long submitCount, long acCount) {
    }

    /** 学生统计。 */
    record StudentStat(Long userId, String studentName, long submitCount, long acCount,
                       LocalDateTime lastSubmitTime) {
    }
}
