package top.everblue.oj.api.user;

import java.util.Collection;
import java.util.Map;

/**
 * 用户 Rating 读写契约 — 跨模块读写用户 rating 的唯一入口。
 * <p>
 * 实现由 oj-system 模块提供（数据归属方），oj-contest 比赛排名固化时使用，
 * 禁止直接注入 UserService 或 User 实体。
 * </p>
 */
public interface UserRatingCommand {

    /** 批量读取用户当前 rating（仅返回存在的用户；缺失默认值由调用方决定）。 */
    Map<Long, Integer> findRatings(Collection<Long> userIds);

    /** 批量写入用户 rating（覆盖式）。 */
    void batchUpdateRatings(Map<Long, Integer> userIdToRating);
}
