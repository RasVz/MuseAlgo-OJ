package com.musealgo.oj.contest.entity.vo;

import java.time.LocalDateTime;
import java.util.List;

/** T8-3 C：链式重放预览（只读）。 */
public record SettlementReplayPreviewVO(
        Long fromContestId,
        String fromContestTitle,
        List<ContestBrief> contests,
        int affectedUsers,
        int totalOldRows,
        List<String> warnings,
        boolean canExecute,
        String confirmation
) {
    public record ContestBrief(Long id, String title, LocalDateTime endTime, Integer settlementNo) {
    }
}
