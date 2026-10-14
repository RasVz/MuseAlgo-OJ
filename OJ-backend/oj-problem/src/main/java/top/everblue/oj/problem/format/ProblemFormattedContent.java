package top.everblue.oj.problem.format;

import java.util.List;

/**
 * 格式化后的标准题目结构：
 * 五个文本字段 + 时限/内存（毫秒/MB）+ 多组公开样例。
 */
public record ProblemFormattedContent(
        String title,
        String content,
        String inputDesc,
        String outputDesc,
        String hint,
        Integer timeLimitMs,
        Integer memoryLimitMb,
        List<FormatSample> samples
) {
}
