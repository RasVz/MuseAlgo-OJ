package com.musealgo.oj.judge.dto;

import java.util.List;

/**
 * 批量重判 dry-run 预览（T8-2a）。
 * <p>只统计，不产生任何写入；执行接口会按同样分类重新校验，避免预览与执行之间的漂移。</p>
 */
public record RejudgePreviewVO(
        String scopeType,
        int matched,
        int eligible,
        int blockedFinalized,
        int blockedRunning,
        int blockedVp,
        int blockedPending,
        int blockedNoTestCases,
        int blockedInFlight,
        int blockedMissingContest,
        int limit,
        boolean overLimit,
        boolean truncated,
        List<Long> sampleSubmissionIds
) {
}
