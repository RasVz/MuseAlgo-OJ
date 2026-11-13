package com.musealgo.oj.api.problem;

/**
 * 相似题目提示。
 *
 * @param score 标题 3-gram Jaccard 相似度，0~1
 */
public record SimilarProblemView(Long problemId, String title, Integer difficulty, double score) {
}
