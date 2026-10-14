package top.everblue.oj.api.user;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用户查询契约 — 跨模块只读访问 User 数据的唯一入口。
 * <p>
 * 消费者通过此接口查询用户信息，禁止直接注入 UserMapper 或 User 实体。
 * </p>
 */
public interface UserQuery {

    /** 按 ID 查询展示信息 */
    Optional<UserDisplayInfo> findDisplayInfo(Long userId);

    /** 批量查询展示信息，返回 userId → UserDisplayInfo 映射 */
    Map<Long, UserDisplayInfo> findDisplayInfoBatch(Collection<Long> userIds);

    /** 按用户名查询 ID */
    Optional<Long> findIdByUsername(String username);

    /**
     * 查询所有关闭"分享提交记录"的用户 ID。
     * <p>
     * 这些用户的提交在"所有人 / 关注的人"视角中应被隐藏（本人和管理员始终可见）。
     * </p>
     */
    List<Long> findUserIdsWithSubmissionsHidden();

    /**
     * 用户是否开启了"分享提交记录"（默认开启，preferences 中 shareSubmissions 缺失视为开启）。
     * <p>
     * 关闭分享的用户，其提交详情（含源码）对其他用户不可见。
     * </p>
     */
    boolean isSubmissionSharingEnabled(Long userId);
}
