package top.everblue.oj.problem.format;

/** 应用预览结果：前端把预览返回的内容原样回传，服务端校验后落库 */
public record ProblemFormatApplyRequest(Long problemId, ProblemFormattedContent formatted) {
}
