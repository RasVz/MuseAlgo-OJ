package top.everblue.oj.contest.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 比赛最终成绩快照，对应 contest_rank 表。
 * <p>
 * 与「实时榜单」是<b>不同生命周期</b>的两份数据：比赛进行中榜单存在 Redis 里、随每次提交变动；
 * 比赛结束后把最终结果固化到本表，用于赛后查询、导出、颁奖与历史回看。
 * 因此本表是<b>终态快照</b>，不会再随迟到的判题消息被修改。
 * </p>
 *
 * <h3>为什么排名要落库而不是永远查 Redis</h3>
 * <p>
 * Redis 榜单 key 有过期时间，且排名结果依赖当时使用的扣分规则；
 * 一场老比赛的榜单必须能在 Redis 清理后仍被查出来，也需要在扣分规则调整后保持历史成绩不变，
 * 所以必须有一份与规则解耦的落库快照。
 * </p>
 *
 * <h3>与模式相关的字段</h3>
 * <ul>
 *   <li>{@code solvedCount / penaltyTime}：ACM 模式的计分字段，OI 模式恒为 0。</li>
 *   <li>{@code totalScore}：OI 模式的得分字段，ACM 模式恒为 0。</li>
 *   <li>{@code rankNum}：已展开的<b>并列名次</b>（并列者取相同值，如 1,1,3），
 *       不是行号，连续两行可能是同一名次。</li>
 * </ul>
 */
@Data
@TableName("contest_rank")
public class ContestRank implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

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
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Long createBy;
    private Long updateBy;
}
