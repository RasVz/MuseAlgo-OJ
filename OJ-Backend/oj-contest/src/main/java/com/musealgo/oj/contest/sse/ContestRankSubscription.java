package com.musealgo.oj.contest.sse;

/** A live SSE audience for one contest and one board type. */
public record ContestRankSubscription(Long contestId, ContestRankBoardType boardType) {
}
