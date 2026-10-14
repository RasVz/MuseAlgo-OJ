package top.everblue.oj.problem.format;

import java.util.List;

/** 批量格式化汇总结果 */
public record ProblemFormatResult(
        int total,
        int succeeded,
        int failed,
        List<ProblemFormatItemResult> items
) {
}
