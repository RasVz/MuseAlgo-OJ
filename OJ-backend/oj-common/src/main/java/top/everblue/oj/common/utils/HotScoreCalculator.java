package top.everblue.oj.common.utils;

import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 帖子热度分算法，产出全局榜与分类榜的排序依据。
 * <p>
 * 打分思路接近 Hacker News 的「互动加权 ÷ 时间衰减」：收藏 &gt; 评论 &gt; 点赞 &gt; 浏览 &gt;& 踩，
 * 把稀缺、高成本的行为（收藏、评论）排在廉价行为（浏览）前面，避免刷浏览量就能上榜；
 * 踩是负权重，让争议帖自然下沉。
 * </p>
 * <p>
 * 置顶（{@code W_TOP}）与加精（{@code W_ESSENCE}）是运营加权，取值刻意远大于常见互动总量，
 * 效果是让运营位在数小时内稳定压住自然热度，而不是和互动量抢榜位。
 * </p>
 * <p>
 * 衰减用 {@code (hours + 2) ^ GRAVITY} 做分母而非指数函数：它对「刚发布」的帖子给出一个有限的分母上界，
 * 避免新帖分数爆炸式领先；{@code PROTECT_HOURS} 保护期内把小时数钉成 0.5，让新帖先聚一波互动再开始衰减。
 * 调参时 {@code GRAVITY} 与保护期是一对，改一个必须连着另一个一起看。
 * </p>
 * <p>
 * {@link #calculateQuick} 与 {@link #calculate} 的公式必须保持一致，前者用于互动更新时直接在 Redis 侧
 * 估算、不查数据库；两者一旦分叉，榜单会出现「刷新一次就变一次」的不一致现象。
 * </p>
 */
@Component
public class HotScoreCalculator {

    // 权重配置（可注入配置文件）
    private static final double W_LIKE = 3.0;
    private static final double W_FAVORITE = 5.0;
    private static final double W_COMMENT = 4.0;
    private static final double W_VIEW = 0.1;
    private static final double W_DISLIKE = 2.0;
    private static final double W_TOP = 10000.0;
    private static final double W_ESSENCE = 5000.0;

    // 重力因子（越大衰减越快）
    private static final double GRAVITY = 1.5;
    // 保护期（小时）
    private static final double PROTECT_HOURS = 2.0;

    /**
     * 计算热度分
     * @param stats 统计值 (views, likes, dislikes, favorites, comments)
     * @param isTop 是否置顶
     * @param isEssence 是否加精
     * @param createTime 创建时间
     */
    public double calculate(Map<String, Integer> stats, boolean isTop, boolean isEssence,
                            LocalDateTime createTime) {
        // 基础互动分
        double interactionScore =
                W_LIKE * stats.getOrDefault("likes", 0) +
                        W_FAVORITE * stats.getOrDefault("favorites", 0) +
                        W_COMMENT * stats.getOrDefault("comments", 0) +
                        W_VIEW * stats.getOrDefault("views", 0) -
                        W_DISLIKE * stats.getOrDefault("dislikes", 0);

        // 运营加权
        double operatorScore = 0;
        if (isTop) operatorScore += W_TOP;
        if (isEssence) operatorScore += W_ESSENCE;

        // 时间衰减计算
        double hours = Duration.between(createTime, LocalDateTime.now()).toMinutes() / 60.0;
        // 保护期内不衰减
        if (hours < PROTECT_HOURS) hours = 0.5;

        double timeDecay = Math.pow(hours + 2, GRAVITY);

        return (interactionScore + operatorScore) / timeDecay;
    }

    /**
     * 预测性计算（用于更新时快速估算，不查DB）
     */
    public double calculateQuick(int views, int likes, int dislikes, int favorites, int comments,
                                 boolean isTop, boolean isEssence, long hoursSinceCreate) {
        double score = (W_LIKE * likes + W_FAVORITE * favorites + W_COMMENT * comments +
                W_VIEW * views - W_DISLIKE * dislikes);
        if (isTop) score += W_TOP;
        if (isEssence) score += W_ESSENCE;

        double effectiveHours = hoursSinceCreate < PROTECT_HOURS ? 0.5 : hoursSinceCreate;
        return score / Math.pow(effectiveHours + 2, GRAVITY);
    }
}
