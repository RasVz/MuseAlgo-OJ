package top.everblue.oj.contest.entity.vo;

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
 * {@code rankNum} 与 {@code totalParticipants} 只在本次 VP 参与者之间可比，
 * 不应与正式榜单的同名次混用。
 * </p>
 */
@Data
public class VpAttemptVO {
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
    private Long remainingSeconds;
}
