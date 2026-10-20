package top.everblue.oj.api.submission;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 虚拟赛（VP）与赛后订正的提交只读契约。
 */
public interface ContestVpQuery {

    /** 已接收的 VP 提交是否仍在判题。 */
    boolean hasPending(Long vpAttemptId);

    /** VP 会话时间窗内每题最好成绩 */
    List<VpProblemScore> findBestScores(Long vpAttemptId);

    /** VP 会话内全部提交，按提交时间升序，用于 ACM 罚时计算。 */
    List<VpSubmission> findSubmissions(Long vpAttemptId);

    /** 赛中每题最好状态（status 最小=AC 优先） */
    List<ContestProblemResult> findContestResults(Long contestId, Long userId);

    /** 赛后在 [from,to] 内 AC 的题目（订正追踪） */
    List<VpProblemScore> findPostContestAc(Long userId, Collection<Long> problemIds,
                                           LocalDateTime from, LocalDateTime to);

    /** VP 会话内某题的最终得分。 */
    record VpProblemScore(Long problemId, int bestScore) {
    }

    /** VP 会话内的单条提交（ACM 罚时计算用）。 */
    record VpSubmission(Long problemId, Byte status, LocalDateTime submittedAt) {
    }

    /** 赛中某题的最好状态（status 最小 = AC 优先）。 */
    record ContestProblemResult(Long problemId, Byte bestStatus) {
    }
}
