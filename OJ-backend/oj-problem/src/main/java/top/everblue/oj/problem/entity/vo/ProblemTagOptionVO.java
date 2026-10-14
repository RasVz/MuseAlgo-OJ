package top.everblue.oj.problem.entity.vo;

import lombok.Data;

/**
 * 标签下拉选项：标签名 + 所属知识维度，用于按维度分组的筛选面板。
 */
@Data
public class ProblemTagOptionVO {
    private String name;
    private String dimension;
}
