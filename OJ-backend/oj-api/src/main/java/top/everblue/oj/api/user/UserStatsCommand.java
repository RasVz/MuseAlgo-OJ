package top.everblue.oj.api.user;

/**
 * 用户社交计数写契约 — 跨模块修改粉丝数/关注数的唯一入口。
 * <p>
 * 实现由 oj-system 模块提供（数据归属方），oj-community 等模块禁止直接注入 UserService。
 * </p>
 */
public interface UserStatsCommand {

    /** 增减粉丝数（delta 为 +1 或 -1）。 */
    void incrementFollowerCount(Long userId, int delta);

    /** 增减关注数（delta 为 +1 或 -1）。 */
    void incrementFollowingCount(Long userId, int delta);
}
