package top.everblue.oj.api.problem;
/** 面向当前认证主体的题目读取/普通练习权限。正式比赛提交另走比赛上下文校验。 */
public interface ProblemAccessQuery {
    void requireReadable(Long problemId);
    void requirePracticeSubmission(Long problemId);
}
