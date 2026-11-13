package com.musealgo.oj.problem.draft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitleSimilarityTest {

    @Test
    void identicalTitlesScoreOne() {
        assertEquals(1.0, TitleSimilarity.score("知识星排队", "知识星排队"), 1e-9);
    }

    @Test
    void punctuationAndSpacesAreIgnored() {
        assertEquals(1.0, TitleSimilarity.score("知识星排队", "《知识星 排队》"), 1e-9);
    }

    @Test
    void reorderedTitleStaysAboveThreshold() {
        double score = TitleSimilarity.score("星海市的最短路径", "最短路径星海市");
        assertTrue(score >= TitleSimilarity.DEFAULT_THRESHOLD, "实际相似度 " + score);
    }

    @Test
    void unrelatedTitlesFallBelowThreshold() {
        double score = TitleSimilarity.score("知识星排队", "二分查找答案");
        assertTrue(score < TitleSimilarity.DEFAULT_THRESHOLD, "实际相似度 " + score);
    }

    @Test
    void shortTitlesStillComparable() {
        assertEquals(1.0, TitleSimilarity.score("A+B", "A+B"), 1e-9);
        assertEquals(0.0, TitleSimilarity.score("A", "B"), 1e-9);
    }

    @Test
    void blankInputScoresZero() {
        assertEquals(0.0, TitleSimilarity.score("", "任何标题"), 1e-9);
        assertEquals(0.0, TitleSimilarity.score(null, null), 1e-9);
    }
}
