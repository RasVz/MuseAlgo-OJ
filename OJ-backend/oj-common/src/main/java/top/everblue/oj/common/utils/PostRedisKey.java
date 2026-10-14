package top.everblue.oj.common.utils;

/**
 * 社区帖子相关的 Redis key 集中定义。
 * <p>
 * 单独抽一个类而不是在各处字符串拼接，是因为 {@code PREFIX} 一旦改动，线上已经写入的
 * 浏览量、点赞集合、热门榜 ZSet 会全部变成读不到的数据（表现为计数归零、热门榜清空），
 * 且不会报错、只会静默出错。把 key 结构收在一处，是为了让改名这个动作必须经过一次全局搜索。
 * </p>
 * <p>
 * 约定：统一以 {@code oj:post} 开头，第二段标明数据类型用途（{@code stat} / {@code hot} / {@code liked}
 * 等），最后一段是实体 id 或分类名。各方法旁的行注释标注了对应的 Redis 数据结构，改结构时要一并更新。
 * </p>
 * <p>
 * 纯静态工具，不持有状态。
 * </p>
 */
public class PostRedisKey {
    private static final String PREFIX = "oj:post";

    // Hash: 帖子实时计数 (views, likes, dislikes, favorites, comments)
    public static String stat(Long postId) {
        return String.format("%s:stat:%d", PREFIX, postId);
    }

    // ZSet: 全局热门榜 (score -> postId)
    public static String hotGlobal() {
        return String.format("%s:hot:global", PREFIX);
    }

    // ZSet: 分类热门榜
    public static String hotCategory(String category) {
        return String.format("%s:hot:category:%s", PREFIX, category);
    }

    // Set: 已点赞用户 (防重复 + 可查看谁赞过)
    public static String likedUsers(Long postId) {
        return String.format("%s:liked:%d", PREFIX, postId);
    }

    // Set: 已收藏用户
    public static String favedUsers(Long postId) {
        return String.format("%s:faved:%d", PREFIX, postId);
    }

    // Set: 已踩用户
    public static String dislikedUsers(Long postId) {
        return String.format("%s:disliked:%d", PREFIX, postId);
    }

    // HyperLogLog: 浏览UV (防刷，允许微小误差)
    public static String viewedUsers(Long postId) {
        return String.format("%s:viewed:%d", PREFIX, postId);
    }

    // String: 帖子热度分缓存（可选，用于快速读取当前分）
    public static String hotScore(Long postId) {
        return String.format("%s:score:%d", PREFIX, postId);
    }
}
