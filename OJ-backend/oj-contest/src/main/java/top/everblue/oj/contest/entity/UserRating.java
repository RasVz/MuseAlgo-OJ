package top.everblue.oj.contest.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 选手评分变化流水，对应 user_rating 表。
 * <p>
 * 每场比赛结束后为每个参赛者落一条记录，构成选手的「评分折线」：
 * {@code rating} 是 Y 轴上的当前分，{@code createTime} 是 X 轴时间，
 * {@code ratingChange} 是本次的涨跌幅（正为涨、负为跌，前端显示成 {@code +20}）。
 * </p>
 *
 * <h3>存流水而不是只存当前分</h3>
 * <p>
 * 只存「用户当前 rating」无法画出历史曲线，也无法在误改计分算法后回溯。
 * 按比赛逐条记录使得选手页面可以展示完整变化轨迹，且每条记录自带当时的 {@code rank}，
 * 便于回看「那场为什么涨了这么多分」。
 * </p>
 *
 * <p>
 * {@code rank} 是 MySQL 8 的保留字，必须用 {@code @TableField("`rank`")} 反引号转义，
 * 不能依赖驼峰转下划线的默认映射。
 * </p>
 */
@Data
@TableName("user_rating")
public class UserRating {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;     // 谁
    private Long contestId;  // 哪场比赛

    private Integer rating;  // 变动后的最终分数 (Y轴数据)
    private Integer ratingChange; // 涨了多少分 (显示在Tip里: +20)

    @TableField("`rank`")
    private Integer rank;    // 排名

    private LocalDateTime createTime; // 变动时间 (X轴数据)

    private LocalDateTime updateTime;
}