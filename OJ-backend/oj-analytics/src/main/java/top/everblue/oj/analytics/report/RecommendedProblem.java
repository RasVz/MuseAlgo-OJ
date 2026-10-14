package top.everblue.oj.analytics.report;

/** 报告「下周推荐题单」区块的单条推荐。 */
public record RecommendedProblem(
        String tagName,
        String title,
        String difficultyLabel
) {}