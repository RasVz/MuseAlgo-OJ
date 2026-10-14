package top.everblue.oj.community.entity.dto;

import lombok.Data;

/**
 * 收藏 / 取消收藏的请求体。
 * <p>
 * 只带目标，不带收藏者：收藏者一律取当前登录态，避免前端传错 userId 造成越权收藏。
 * </p>
 */
@Data
public class UserFavoriteDTO {
    // 被收藏的内容 ID (比如题单 ID)
    private Long targetId;

    // 目标类型 (比如 2 代表题单)
    private Integer targetType;
}