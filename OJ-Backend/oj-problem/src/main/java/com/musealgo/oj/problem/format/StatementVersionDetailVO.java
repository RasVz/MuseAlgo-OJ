package com.musealgo.oj.problem.format;

import java.time.LocalDateTime;
import java.util.List;

/** 版本详情：正文、样例与质量检查结果，用于右侧预览。 */
public record StatementVersionDetailVO(
        Long id,
        Long problemId,
        int versionNo,
        String status,
        String source,
        String title,
        String content,
        String inputDesc,
        String outputDesc,
        String hint,
        Integer timeLimit,
        Integer memoryLimit,
        List<FormatSample> samples,
        List<StatementIssue> issues,
        Long baseVersionId,
        Long jobId,
        String note,
        Long createdBy,
        LocalDateTime createTime
) {
}
