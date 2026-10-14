package top.everblue.oj.api.problem;

/**
 * 题目摘要 — 跨模块只读投射，供报告推荐题单等场景使用。
 * 不含题面内容，避免大字段传输。
 */
public record ProblemDigest(
        Long id,
        String title,
        Integer difficulty,   // 难度分数 800-3000
        Boolean isPublic
) {}