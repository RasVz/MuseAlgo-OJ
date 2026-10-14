package top.everblue.oj.community.entity.vo;

import lombok.Data;

/** 文章详情中供读者跳转练习的精简题目数据。 */
@Data
public class RelatedProblemVO {
    private Long id;
    private String title;
    private Integer difficulty;
}
