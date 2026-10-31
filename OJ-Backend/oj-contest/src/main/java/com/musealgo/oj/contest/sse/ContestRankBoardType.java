package com.musealgo.oj.contest.sse;

/** The two independently queried contest ranking views. */
public enum ContestRankBoardType {
    OFFICIAL("official"),
    GLOBAL("global");

    private final String wireValue;

    ContestRankBoardType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
