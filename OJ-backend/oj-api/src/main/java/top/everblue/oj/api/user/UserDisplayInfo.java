package top.everblue.oj.api.user;

/**
 * 用户展示信息 — 跨模块只读投射，用于 VO/响应构建。
 * 消费者不直接访问 User 实体，避免耦合。
 */
public record UserDisplayInfo(
        Long id,
        String username,
        String nickname,
        String avatar,
        String motto,
        String school,
        Integer acceptedCount
) {
}
