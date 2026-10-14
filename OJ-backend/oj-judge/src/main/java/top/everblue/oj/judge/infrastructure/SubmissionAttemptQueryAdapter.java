package top.everblue.oj.judge.infrastructure;

import org.springframework.stereotype.Service;
import top.everblue.oj.api.submission.SubmissionAttemptQuery;
import top.everblue.oj.judge.mapper.SubmissionMapper;

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
}
