package com.musealgo.oj.problem.format;

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

    /** 同一份内容的结构化视图：版本快照、哈希基线与质量检查都基于它。 */
    public ProblemStatementSnapshot asSnapshot() {
        return new ProblemStatementSnapshot(title, content, inputDesc, outputDesc, hint,
                timeLimitMs, memoryLimitMb, samples == null ? List.of() : samples);
    }
}
