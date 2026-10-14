package top.everblue.oj.judge.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.insight.ClassInsightQuery;
import top.everblue.oj.judge.mapper.SubmissionMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 班级学情聚合契约适配器 — oj-judge 侧实现 {@link ClassInsightQuery}。
 * <p>
 * SQL 聚合逻辑在 SubmissionMapper 中（数据源 submission 表），
 * oj-analytics 通过本契约消费，不直接注入 SubmissionMapper。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class ClassInsightQueryAdapter implements ClassInsightQuery {

    private final SubmissionMapper submissionMapper;

    @Override
    public ClassSummary summary(Long classId, LocalDateTime from, LocalDateTime to) {
        Map<String, Object> row = submissionMapper.selectClassInsightSummary(classId, from, to);
        if (row == null) {
            return new ClassSummary(0, 0, 0, 0);
        }
        return new ClassSummary(
                toLong(row.get("totalSubmissions")),
                toLong(row.get("acceptedCount")),
                toLong(row.get("activeStudentCount")),
                toLong(row.get("problemCount"))
        );
    }

    @Override
    public List<DailyStat> daily(Long classId, LocalDateTime from, LocalDateTime to) {
        return submissionMapper.selectClassInsightDaily(classId, from, to).stream()
                .map(row -> new DailyStat(
                        String.valueOf(row.get("submitDate")),
                        toLong(row.get("submitCount")),
                        toLong(row.get("acCount"))
                ))
                .toList();
    }

    @Override
    public List<ProblemStat> problems(Long classId, LocalDateTime from, LocalDateTime to) {
        return submissionMapper.selectClassInsightProblems(classId, from, to).stream()
                .map(row -> new ProblemStat(
                        row.get("problemId") == null ? null : ((Number) row.get("problemId")).longValue(),
                        row.get("problemTitle") == null ? null : String.valueOf(row.get("problemTitle")),
                        toLong(row.get("submitCount")),
                        toLong(row.get("acCount"))
                ))
                .toList();
    }

    @Override
    public List<StudentStat> students(Long classId, LocalDateTime from, LocalDateTime to) {
        return submissionMapper.selectClassInsightStudents(classId, from, to).stream()
                .map(row -> new StudentStat(
                        row.get("userId") == null ? null : ((Number) row.get("userId")).longValue(),
                        row.get("studentName") == null ? null : String.valueOf(row.get("studentName")),
                        toLong(row.get("submitCount")),
                        toLong(row.get("acCount")),
                        toLocalDateTime(row.get("lastSubmitTime"))
                ))
                .toList();
    }

    private static long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp ts) return ts.toLocalDateTime();
        if (value instanceof java.sql.Date d) return d.toLocalDate().atStartOfDay();
        if (value instanceof java.util.Date d) return new Timestamp(d.getTime()).toLocalDateTime();
        if (value instanceof LocalDateTime ldt) return ldt;
        return null;
    }
}
