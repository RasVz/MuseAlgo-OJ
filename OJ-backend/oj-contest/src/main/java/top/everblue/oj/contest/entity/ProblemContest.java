package top.everblue.oj.contest.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 竞赛-题目关联实体
 * <p>
 * 「一场比赛包含哪些题」的配置载体，是 contest 与 problem 的多对多中间表。
 * 之所以不用直接读题库，是因为<b>同一道题在不同比赛中可以有不同分值、不同顺序、
 * 不同可见性</b>——这些属性属于「本场比赛中的这道题」，不属于题目本身。
 * </p>
 * <p>
 * 另有两处业务语义需要留意：
 * </p>
 * <ul>
 *   <li>{@code score} 是本场分值，OI 模式下正是它决定该题最高分的上限；</li>
 *   <li>{@code visible} 控制题目在赛程页面是否展示，常用于「先只放前两题、临近开赛再放出其余」
 *       的渐进式放题。</li>
 * </ul>
 * <p>
 * 末尾三个字段标注为「非数据库字段」，实际是通过联表查询填充的展示用冗余，
 * 写库时必须排除，仅供列表页一次性返回，避免前端二次请求。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
public class ProblemContest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 竞赛ID
     */
    private Long contestId;

    /**
     * 题目ID
     */
    private Long problemId;

    /**
     * 题目在竞赛中的顺序（如第1题、第2题）
     */
    private Integer problemOrder;

    /**
     * 该题目在本场竞赛中的分值
     */
    private Integer score;

    /**
     * 是否可见：0-隐藏，1-可见
     */
    private Integer visible;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    /**
     * 逻辑删除：0-未删除，1-已删除
     */
    private Integer isDeleted;

    // ========== 非数据库字段（联表查询用）==========

    /**
     * 题目标题（来自problem表）
     */
    private String problemTitle;

    /**
     * 竞赛标题（来自contest表）
     */
    private String contestTitle;

    /**
     * 题目难度（来自problem表）
     */
    private Integer difficulty;
}
