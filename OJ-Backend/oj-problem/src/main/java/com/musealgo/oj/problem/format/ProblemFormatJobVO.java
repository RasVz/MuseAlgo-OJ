package com.musealgo.oj.problem.format;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量整理任务视图：列表只带轻量摘要，单题完整提案走
 * {@code GET /problem/format/jobs/{jobId}/items/{problemId}} 单独拉取。
 * <p>
 * 这样轮询 50 题的任务时，响应仍然只有几 KB，不会每 2 秒回传上百 KB 的题面正文。
 * </p>
 */
public record ProblemFormatJobVO(
        Long id,
        String status,
        boolean ipMode,
        boolean autoApply,
        int total,
        int processed,
        int applied,
        int review,
        int failed,
        int skipped,
        boolean cancelRequested,
        String message,
        LocalDateTime createTime,
        LocalDateTime finishTime,
        List<ProblemFormatJobItemVO> items
) {
}
