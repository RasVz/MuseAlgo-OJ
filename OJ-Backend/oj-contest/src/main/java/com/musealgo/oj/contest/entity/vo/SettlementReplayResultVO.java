package com.musealgo.oj.contest.entity.vo;

import java.time.LocalDateTime;

/** T8-3 C：链式重放结果。 */
public record SettlementReplayResultVO(
        Long replayId,
        Long fromContestId,
        int contestCount,
        int affectedUsers,
        LocalDateTime finishedAt
) {
}
