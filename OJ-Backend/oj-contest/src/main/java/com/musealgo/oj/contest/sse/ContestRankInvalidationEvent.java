package com.musealgo.oj.contest.sse;

/** Transaction-aware request to refresh one authoritative ranking query. */
public record ContestRankInvalidationEvent(Long contestId, ContestRankBoardType boardType) {
}
