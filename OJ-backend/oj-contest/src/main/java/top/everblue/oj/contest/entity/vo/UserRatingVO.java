package top.everblue.oj.contest.entity.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 选手评分曲线上的一点，供前端直接画折线图。
 * <p>
 * 相比持久化的 {@link top.everblue.oj.contest.entity.UserRating}，本 VO 多做了两件「面向图表」的事：
 * </p>
 * <ul>
 *   <li>把比赛名称与 ID 一并带出，让每个数据点的 tooltip 能显示是哪场比赛引起的分数变化；</li>
 *   <li>{@code date} 用 {@code yyyy-MM-dd} 格式化为字符串并在注解里固定 {@code GMT+8}，
 *       前端无需再做时区换算。若去掉 timezone，图表在不同浏览器时区下会整体偏移。</li>
 * </ul>
 */
@Data
public class UserRatingVO {
    private Integer rating;       // 当前分数 (Y轴)
    private Integer ratingChange; // 变动幅度 (如 +50)
    private Integer rank;         // 比赛排名
    private String contestName;   // 比赛名称 (鼠标悬停显示)
    private Long contestId;       // 点击跳转比赛详情用

    // 格式化时间，直接给前端 "2026-01-14" 这样的字符串
    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    private LocalDateTime date;   // 比赛时间 (X轴)
}