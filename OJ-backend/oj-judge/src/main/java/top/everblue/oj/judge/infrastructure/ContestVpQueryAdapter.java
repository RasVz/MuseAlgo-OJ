package top.everblue.oj.judge.infrastructure;

import org.springframework.stereotype.Service;
import top.everblue.oj.api.submission.ContestVpQuery;
import top.everblue.oj.judge.mapper.SubmissionMapper;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/** 虚拟赛与赛后订正契约实现。 */
@Service
public class ContestVpQueryAdapter implements ContestVpQuery {

    private final SubmissionMapper submissionMapper;

    public ContestVpQueryAdapter(SubmissionMapper submissionMapper) {
        this.submissionMapper = submissionMapper;
    }

    @Override
    public boolean hasPending(Long vpAttemptId) {
        return submissionMapper.countPendingVp(vpAttemptId) > 0;
    }

    @Override
    public List<VpProblemScore> findBestScores(Long vpAttemptId) {
        return submissionMapper.selectVpBestScores(vpAttemptId).stream()
                .map(row -> new VpProblemScore(
                        ((Number) row.get("problemId")).longValue(),
                        row.get("bestScore") == null ? 0 : ((Number) row.get("bestScore")).intValue()))
                .toList();
    }

    @Override
    public List<VpSubmission> findSubmissions(Long vpAttemptId) {
        return submissionMapper.selectVpSubmissions(vpAttemptId).stream()
                .map(submission -> new VpSubmission(
                        submission.getProblemId(), submission.getStatus(), submission.getCreateTime()))
                .toList();
    }

    @Override
    public List<ContestProblemResult> findContestResults(Long contestId, Long userId) {
        return submissionMapper.selectContestProblemResults(contestId, userId).stream()
                .map(row -> new ContestProblemResult(
                        ((Number) row.get("problemId")).longValue(),
                        row.get("bestStatus") == null ? null : ((Number) row.get("bestStatus")).byteValue()))
                .toList();
    }

    @Override
    public List<VpProblemScore> findPostContestAc(Long userId, Collection<Long> problemIds,
                                                  LocalDateTime from, LocalDateTime to) {
        if (problemIds == null || problemIds.isEmpty()) {
            return List.of();
        }
        return submissionMapper.selectPostContestAc(userId, problemIds, from, to).stream()
                .map(row -> new VpProblemScore(
                        ((Number) row.get("problemId")).longValue(),
                        row.get("acTime") == null ? 0 : 1))
                .toList();
    }
}
