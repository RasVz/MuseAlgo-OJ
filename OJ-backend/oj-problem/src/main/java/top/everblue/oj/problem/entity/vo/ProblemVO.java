package top.everblue.oj.problem.entity.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import top.everblue.oj.problem.entity.Problem;

/**
 * 题目视图对象
 * 用于在列表展示时携带用户特定的状态信息
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProblemVO extends Problem {

    // 当前用户的做题状态
    // 0: 未开始, 1: AC(已通过), 2: 尝试过未通过
    private Integer userStatus;
}