package top.everblue.oj.contest.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 虚拟赛（VP）作答会话。
 * <p>
 * VP 让用户<b>脱离真实赛程</b>单独重赛一场已结束的考试：可以自由决定开始时刻，
 * 系统为其单独计时、单独出榜，且<b>绝不混入正式榜单</b>——成绩消费者会识别
 * submission 上的 vpAttemptId 并跳过正式榜更新。
 * </p>
 * <p>
 * 一次 VP = 一行记录，其成绩字段含义与 {@link ContestRank} 一致，但生命周期由
 * {@code status} 驱动而非比赛时间：
 * </p>
 * <ul>
 *   <li>{@link #STATUS_RUNNING}：已开始、计时中，尚未交卷。</li>
 *   <li>{@link #STATUS_FINISHED}：已交卷冻结，此时 {@code endTime}、{@code submitTime} 与成绩字段才最终确定。</li>
 * </ul>
 * <p>
 * {@code totalParticipants} 与 {@code rankNum} 是<b>本次 VP 内的相对值</b>，
 * 基数是「同场参加 VP 的人数」而非正式参赛人数，跨 VP 无可比性。
 * </p>
 */
@Getter
@Setter
@TableName("vp_attempt")
public class VpAttempt implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * VP 进行中：已开始计时，未交卷。取值为字符串常量而非枚举，
     * 因为该字段与数据库列按字符串比对，新增状态不应牵动已落库的历史数据。
     */
    public static final String STATUS_RUNNING = "RUNNING";

    /**
     * VP 已交卷：计时停止，成绩与名次冻结，之后的提交不再计入本次 VP。
     */
    public static final String STATUS_FINISHED = "FINISHED";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("contest_id")
    private Long contestId;

    @TableField("user_id")
    private Long userId;

    private String status;

    @TableField("start_time")
    private LocalDateTime startTime;

    @TableField("end_time")
    private LocalDateTime endTime;

    @TableField("submit_time")
    private LocalDateTime submitTime;

    private Integer score;

    @TableField("solved_count")
    private Integer solvedCount;

    @TableField("penalty_time")
    private Integer penaltyTime;

    @TableField("rank_num")
    private Integer rankNum;

    @TableField("total_participants")
    private Integer totalParticipants;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
