package top.everblue.oj.api.profile;

/**
 * 单个 tag 维度下某学生的聚合统计。
 * <p>
 * 由 {@link ProfileQuery#aggregateByTag} 返回，不含 source code 等敏感信息。
 * 所有字段可安全序列化为 JSON 供前端或报告模板使用。
 */
public record TagStat(
        Long tagId,
        String tagName,
        Long parentId,
        String dimension,
        int attempted,          // 尝试过的不同题目数
        int passed,             // AC 的题目数
        int firstTryPassed,     // 第一次提交就 AC 的题目数
        int totalSubmissions,   // 该 tag 下的总提交次数
        double avgAcTimeMs,     // AC 题目的平均耗时（ms）
        int maxDifficulty       // 尝试过的最高难度（0-3）
) {}