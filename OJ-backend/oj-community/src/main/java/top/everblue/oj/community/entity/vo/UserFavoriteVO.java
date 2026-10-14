package top.everblue.oj.community.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 题单被收藏后的聚合出参。
 * <p>
 * 跨模块拼装（题单基本信息来自题单模块、{@code favoriteCount} 来自社区侧收藏表），
 * 因此不复用任何单表实体，而是声明一个只含对外字段的扁平视图。
 * </p>
 */
@Data
public class UserFavoriteVO {

    /**
     * 题单 ID（用于前端跳转详情）
     */
    private Long targetId;

    /**
     * 创建人 ID
     */
    private Long creatorId;

    /**
     * 创建人用户名
     */
    private String creatorUsername;

    /**
     * 题目数量
     */
    private Integer problemCount;

    /**
     * 收藏数
     */
    private Integer favoriteCount;

    /**
     * 题单标题
     */
    private String title;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
