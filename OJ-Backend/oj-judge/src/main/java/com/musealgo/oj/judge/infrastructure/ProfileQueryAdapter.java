package com.musealgo.oj.judge.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.musealgo.oj.api.profile.ProfileQuery;
import com.musealgo.oj.api.profile.ProfileQuery.DifficultyCount;
import com.musealgo.oj.api.profile.ProfileQuery.PracticeOverview;
import com.musealgo.oj.api.profile.ProfileQuery.ProblemAttemptRow;
import com.musealgo.oj.api.profile.TagStat;
import com.musealgo.oj.judge.mapper.SubmissionMapper;

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

    // ------------------------------------------------------------------
    // 练习分析契约（T7）—— 实现依赖 SubmissionMapper 的 selectPractice* 查询
    // ------------------------------------------------------------------

    @Override
    public PracticeOverview practiceOverview(Long userId, LocalDateTime from, LocalDateTime to) {
        PracticeOverview overview = submissionMapper.selectPracticeOverview(userId, from, to);
        // 聚合查询理论上必返回一行（COUNT 不会为 null 行），兜底只是防御 Mapper/代理返回 null
        return overview != null ? overview : new PracticeOverview(0, 0, 0, 0, 0.0, 0);
    }

    @Override
    public List<TagStat> practiceByTag(Long userId, LocalDateTime from, LocalDateTime to) {
        List<TagStat> stats = submissionMapper.selectPracticeByTag(userId, from, to);
        return stats != null ? stats : List.of();
    }

    @Override
    public Map<String, DimensionSummary> practiceByDimension(Long userId, LocalDateTime from, LocalDateTime to) {
        List<Map<String, Object>> rows = submissionMapper.selectPracticeByDimension(userId, from, to);
        Map<String, DimensionSummary> result = new HashMap<>();
        if (rows == null) {
            return result;
        }
        for (Map<String, Object> row : rows) {
            result.put((String) row.get("dimension"), new DimensionSummary(
                    toInt(row.get("attempted")),
                    toInt(row.get("passed")),
                    toInt(row.get("firstTryPassed")),
                    toInt(row.get("totalSubmissions"))
            ));
        }
        return result;
    }

    @Override
    public List<DifficultyCount> practiceDifficulty(Long userId, LocalDateTime from, LocalDateTime to) {
        List<DifficultyCount> counts = submissionMapper.selectPracticeDifficulty(userId, from, to);
        return counts != null ? counts : List.of();
    }

    @Override
    public List<ProblemAttemptRow> practiceSolvedProblems(
            Long userId, LocalDateTime from, LocalDateTime to, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<ProblemAttemptRow> rows = submissionMapper.selectPracticeSolvedProblems(userId, from, to, limit);
        return rows != null ? rows : List.of();
    }

    @Override
    public List<ProblemAttemptRow> practiceTriedProblems(
            Long userId, LocalDateTime from, LocalDateTime to, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<ProblemAttemptRow> rows = submissionMapper.selectPracticeTriedProblems(userId, from, to, limit);
        return rows != null ? rows : List.of();
    }

    private static int toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(value));
    }
}