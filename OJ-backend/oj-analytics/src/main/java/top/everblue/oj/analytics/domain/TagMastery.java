package top.everblue.oj.analytics.domain;

/**
 * 单个 tag 的掌握度结果——在 TagStat 基础上增加计算后的 mastery 分和等级。
 */
public record TagMastery(
        Long tagId,
        String tagName,
        Long parentId,
        String dimension,
        int attempted,
        int passed,
        int firstTryPassed,
        int totalSubmissions,
        double avgAcTimeMs,
        int maxDifficulty,
        int mastery,                  // 0-100
        MasteryLevel masteryLevel
) {
    /**
     * 从原始 TagStat 构建带掌握度的版本。
     */
    public static TagMastery from(top.everblue.oj.api.profile.TagStat stat) {
        int mastery = MasteryCalculator.mastery(stat);
        return new TagMastery(
                stat.tagId(), stat.tagName(), stat.parentId(), stat.dimension(),
                stat.attempted(), stat.passed(), stat.firstTryPassed(),
                stat.totalSubmissions(), stat.avgAcTimeMs(), stat.maxDifficulty(),
                mastery, MasteryLevel.of(mastery)
        );
    }
}