package top.everblue.oj.problem.format;

/** 单道题的格式化结果 */
public record ProblemFormatItemResult(Long problemId, String title, boolean success, String message) {

    public static ProblemFormatItemResult success(Long problemId, String title) {
        return new ProblemFormatItemResult(problemId, title, true, "格式化成功");
    }

    public static ProblemFormatItemResult failure(Long problemId, String message) {
        return new ProblemFormatItemResult(problemId, null, false, message);
    }
}
