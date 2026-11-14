package com.musealgo.oj.judge.infrastructure;

import org.springframework.stereotype.Service;
import com.musealgo.oj.api.submission.SubmissionAttemptQuery;
import com.musealgo.oj.judge.mapper.SubmissionMapper;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/** 训练试卷会话编程题得分契约实现。 */
@Service
public class SubmissionAttemptQueryAdapter implements SubmissionAttemptQuery {

    private final SubmissionMapper submissionMapper;

    public SubmissionAttemptQueryAdapter(SubmissionMapper submissionMapper) {
        this.submissionMapper = submissionMapper;
    }

    @Override
    public List<SubmissionAttemptScore> findBestScores(
            Long userId, Collection<Long> problemIds,
            LocalDateTime from, LocalDateTime to) {
        return submissionMapper.selectBestScoresByProblems(userId, problemIds, from, to).stream()
                .map(row -> new SubmissionAttemptScore(
                        ((Number) row.get("problemId")).longValue(),
                        row.get("bestScore") == null ? 0 : ((Number) row.get("bestScore")).intValue()))
                .toList();
    }

    @Override
    public long countPending(Long userId, Collection<Long> problemIds,
                             LocalDateTime from, LocalDateTime to) {
        return submissionMapper.countPendingExamScores(userId, problemIds, from, to);
    }

    @Override
    public List<SubmissionAttemptScore> findBestScoresByAttempt(Long attemptId, Collection<Long> problemIds) {
        return submissionMapper.selectBestScoresByAttempt(attemptId, problemIds).stream()
                .map(row -> new SubmissionAttemptScore(
                        ((Number) row.get("problemId")).longValue(),
                        row.get("bestScore") == null ? 0 : ((Number) row.get("bestScore")).intValue()))
                .toList();
    }

    @Override
    public long countPendingByAttempt(Long attemptId, Collection<Long> problemIds) {
        return submissionMapper.countPendingExamAttemptScores(attemptId, problemIds);
    }

    @Override
    public List<SubmissionAttemptSummary> summarizeByAttempt(Long attemptId, Collection<Long> problemIds) {
        return submissionMapper.selectAttemptSummary(attemptId, problemIds).stream()
                .map(row -> new SubmissionAttemptSummary(
                        ((Number) row.get("problemId")).longValue(),
                        row.get("bestScore") == null ? 0 : ((Number) row.get("bestScore")).intValue(),
                        row.get("attemptCount") == null ? 0 : ((Number) row.get("attemptCount")).intValue(),
                        row.get("pendingCount") == null ? 0 : ((Number) row.get("pendingCount")).intValue()))
                .toList();
    }
}
