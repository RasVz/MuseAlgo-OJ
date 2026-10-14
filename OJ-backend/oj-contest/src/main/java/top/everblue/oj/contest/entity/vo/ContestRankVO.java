package top.everblue.oj.contest.entity.vo;

import lombok.Data;

/**
 * 单行榜单的对外视图，同时服务两种数据源。
 * <p>
 * 同一个对象既由 Redis ZSet 实时组装（比赛进行中），也由 contest_rank 表反序列化（赛后），
 * 所以字段是一份并集：
 * </p>
 * <ul>
 *   <li>实时榜下 {@code ratingBefore / ratingAfter / award} 恒为 {@code null}——
 *       评分与奖项要等比赛结束才能确定，实时阶段给出 {@code null} 让前端明确区分「还没算」与「是0」；
 *       {@code isVisible} 恒为 1，实时榜不做隐藏（可见性已在接口层拦截）。</li>
 *   <li>落库榜下 {@code totalScore} 仅 OI 模式有意义，ACM 模式为 0。</li>
 * </ul>
 * <p>
 * {@code rankNum} 是已展开的并列名次（1,1,3），不是行号，前端可直接展示。
 * </p>
 */
@Data
public class ContestRankVO {
    private Long contestId;
    private Long userId;
    private Integer rankNum;
    private Integer solvedCount;
    private Integer penaltyTime;
    private Integer totalScore;
    private Integer ratingBefore;
    private Integer ratingAfter;
    private String award;
    private Integer isVisible;
}
