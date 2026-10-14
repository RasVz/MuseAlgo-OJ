package top.everblue.oj.api.contest;

/** 将客户端请求的比赛上下文解析为权威提交上下文（比赛 vs 虚拟赛）。 */
public interface ContestSubmissionContextResolver {

    /**
     * 解析比赛/虚拟赛归属。
     *
     * @param contestId 比赛 ID（可能为 null）
     * @param problemId 题目 ID
     * @param userId    提交人
     * @return 权威提交上下文（比赛 ID + 命中的 VP 会话 ID，VP 会话可为空）
     */
    SubmissionContext resolve(Long contestId, Long problemId, Long userId);

    /** 权威提交上下文：比赛 ID + 命中的 VP 会话 ID（非 VP 场景 vpAttemptId 为空）。 */
    record SubmissionContext(Long contestId, Long vpAttemptId) {
    }
}
