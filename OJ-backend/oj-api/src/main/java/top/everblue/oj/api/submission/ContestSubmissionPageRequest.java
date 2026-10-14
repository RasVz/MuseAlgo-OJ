package top.everblue.oj.api.submission;

/** 比赛提交历史分页查询请求 — 比赛模块读取已判提交记录时的过滤条件载体。 */
public record ContestSubmissionPageRequest(
        Long contestId,
        int page,
        int size,
        boolean mineOnly,
        Long currentUserId,
        Long problemId,
        String language,
        Integer status
) {
}
