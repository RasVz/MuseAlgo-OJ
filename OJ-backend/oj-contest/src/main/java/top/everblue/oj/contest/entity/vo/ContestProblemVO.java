package top.everblue.oj.contest.entity.vo;

import lombok.Data;

/**
 * 比赛题目列表的一行。
 * <p>
 * 除题目自身的标题、顺序、分值外，额外带出 {@code status}——
 * 「<b>当前登录用户</b>是否已通过本题」，需要按登录用户联查提交记录得到，
 * 因此它不是题目的属性，而是「题目 × 当前用户」的组合属性。
 * </p>
 * <p>
 * 由此带来一条实现约束：该字段<b>只在有登录态时才有意义</b>，
 * 未登录访问时应返回空值而不是 {@code AC}，否则会把别人的通过状态展示给游客。
 * </p>
 */
@Data
public class ContestProblemVO {
    private Long problemId;
    private String title;
    private Integer problemOrder;
    private Integer score;
    private String status;           // 当前用户是否已通过（需要联表查询）
}