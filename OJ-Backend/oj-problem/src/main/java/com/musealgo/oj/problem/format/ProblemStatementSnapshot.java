package com.musealgo.oj.problem.format;

import java.util.List;

/**
 * 题面原文快照：预览时一并返回给前端做对照，并作为质量检查与哈希基线的输入。
 * <p>
 * 与 {@link ProblemFormattedContent} 字段一一对应，便于前端左右分栏比较。
 * </p>
 */
public record ProblemStatementSnapshot(
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
