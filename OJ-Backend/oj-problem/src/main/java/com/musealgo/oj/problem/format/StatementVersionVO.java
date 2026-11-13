package com.musealgo.oj.problem.format;

import java.time.LocalDateTime;

/**
 * 版本列表项：足够渲染时间线，不携带正文（正文走详情接口）。
 *
 * @param live     是否为题目当前使用的版本
 * @param drifted  仅 LIVE 有意义：题面在编辑器里被改过，与快照不一致
 */
public record StatementVersionVO(
        Long id,
        Long problemId,
        int versionNo,
        String status,
        String source,
        String title,
        String summary,
        Integer timeLimit,
        Integer memoryLimit,
        int sampleCount,
        Long baseVersionId,
        Long jobId,
        int issueErrors,
        int issueWarnings,
        Long createdBy,
        LocalDateTime createTime,
        boolean live,
        boolean drifted
) {
}
