package top.everblue.oj.judge.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.profile.ProfileQuery;
import top.everblue.oj.api.profile.TagStat;
import top.everblue.oj.judge.mapper.SubmissionMapper;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter that serves the {@link ProfileQuery} read-only contract by delegating
 * to the SubmissionMapper (owned by the judge module).
 * <p>
 * Follows the same pattern as {@link SubmissionHistoryQueryAdapter}.
 */
@Service
@RequiredArgsConstructor
public class ProfileQueryAdapter implements ProfileQuery {

    private final SubmissionMapper submissionMapper;

    @Override
    public List<TagStat> aggregateByTag(Long userId, LocalDateTime from, LocalDateTime to) {
        return submissionMapper.selectProfileByTag(userId, from, to);
    }

    @Override
    public Map<String, DimensionSummary> aggregateByDimension(Long userId, LocalDateTime from, LocalDateTime to) {
        List<Map<String, Object>> rows = submissionMapper.selectProfileByDimension(userId, from, to);
        Map<String, DimensionSummary> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String dim = (String) row.get("dimension");
            result.put(dim, new DimensionSummary(
                    toInt(row.get("attempted")),
                    toInt(row.get("passed")),
                    toInt(row.get("firstTryPassed")),
                    toInt(row.get("totalSubmissions"))
            ));
        }
        return result;
    }

    @Override
    public WeekOverview weekOverview(Long userId, LocalDateTime from, LocalDateTime to) {
        WeekOverview overview = submissionMapper.selectWeekOverview(userId, from, to);
        return overview != null ? overview : new WeekOverview(0, 0, 0, 0);
    }

    @Override
    public java.util.Set<Long> findSolvedProblemIds(Long userId) {
        java.util.Set<Long> ids = submissionMapper.selectSolvedProblemIds(userId);
        return ids != null ? ids : java.util.Collections.emptySet();
    }

    @Override
    public Map<Long, Map<String, DimensionSummary>> aggregateClassByDimension(
            java.util.Collection<Long> studentIds, LocalDateTime from, LocalDateTime to) {
        if (studentIds == null || studentIds.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        List<Map<String, Object>> rows = submissionMapper.selectClassProfileByDimension(studentIds, from, to);
        Map<Long, Map<String, DimensionSummary>> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Long userId = ((Number) row.get("userId")).longValue();
            String dim = (String) row.get("dimension");
            result.computeIfAbsent(userId, k -> new HashMap<>())
                    .put(dim, new DimensionSummary(
                            toInt(row.get("attempted")),
                            toInt(row.get("passed")),
                            toInt(row.get("firstTryPassed")),
                            toInt(row.get("totalSubmissions"))
                    ));
        }
        return result;
    }

    private static int toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(value));
    }
}