package top.everblue.oj.contest.entity.vo;

import lombok.Data;

/**
 * 平台总排行榜的一行。
 * <p>
 * 与 {@link ContestRankVO} 是两套<b>不同用途</b>的榜单：本类是「跨比赛、按选手累计积分」
 * 的总榜，体现的是用户画像与荣誉；而比赛榜是「单场比赛内、按本场表现」的临时排名。
 * 因此这里的 {@code rating} 与{@code acceptedCount} 都是累计值，不是本场数据。
 * </p>
 */
@Data
public class RankVO {

    /**
     * 名次 (1, 2, 3...)
     */
    private Integer rank;

    /**
     * 用户ID (点击头像跳转个人主页用)
     */
    private Long id;

    /**
     * 用户昵称
     */
    private String username;

    /**
     * 用户头像 URL
     */
    private String avatar;

    /**
     * 学校/机构
     */
    private String school;

    /**
     * 个性签名/座右铭 (排行榜通常会展示这个，增加趣味性)
     */
    private String motto;

    /**
     * 积分 / 评分
     */
    private Integer rating;

    /**
     * 刷题数
     */
    private Integer acceptedCount;
}
