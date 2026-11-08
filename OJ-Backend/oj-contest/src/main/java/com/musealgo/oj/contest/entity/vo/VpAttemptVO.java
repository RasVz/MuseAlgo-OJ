package com.musealgo.oj.contest.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 虚拟赛（VP）会话详情，同时覆盖「进行中」与「已结束」两种界面的数据需求。
 * <p>
 * 关键字段是 {@code remainingSeconds}：进行中的 VP 页面需要倒计时，
 * 而这个值<b>不能在前端算</b>——它依赖「服务端当前时间 − startTime」，
 * 若前端自行用本地时间计算，用户改系统时间就能获得额外时长。
 * 因此由服务端算出剩余秒数下发，已结束的 VP 则为 0 或负值。
 * </p>
 * <p>
 * {@code rankNum} 是 VP 结算时与正式榜（仅 OFFICIAL）比较得到的<b>参考名次</b>，
 * 分母 {@code totalParticipants} = 正式榜人数 + 本人；它既不是同场多个 VP 之间的排名，
 * 也不是全局榜当前的合并名次。{@code rankBasis} 显式标记这一口径，历史会话不落库该字段，
 * 读取时统一填充，因此无需重算旧数据。
 * </p>
 */
@Data
public class VpAttemptVO {
    /**
     * {@code rankNum} 的比较基准：与正式榜 OFFICIAL 选手比较，正式人数 + 本人为分母。
     * 该值只表达字段语义，不写入 {@code vp_attempt} 表。
     */
    public static final String RANK_BASIS_OFFICIAL_REFERENCE = "OFFICIAL_REFERENCE";

    private Long id;
    private Long contestId;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime submitTime;
    private Integer score;
    private Integer solvedCount;
    private Integer penaltyTime;
    private Integer rankNum;
    private Integer totalParticipants;
    /** {@code rankNum} 的比较基准；当前恒为 {@link #RANK_BASIS_OFFICIAL_REFERENCE}。 */
    private String rankBasis;
    private Long remainingSeconds;
}
