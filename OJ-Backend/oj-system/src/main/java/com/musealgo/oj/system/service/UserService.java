package com.musealgo.oj.system.service;

import com.musealgo.oj.system.entity.User;
import com.baomidou.mybatisplus.spring.service.IService;

import java.util.List;

/**
 * <p>
 * 用户表 服务类
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
public interface UserService extends IService<User> {
    public Integer registerCheck(User user);

    public boolean register(User user);

    boolean updateProfile(Long userId, User user);

    boolean changePassword(Long userId, String oldPassword, String newPassword);

    /** Persists an already encoded administrator-set password and revokes prior sessions. */
    boolean updatePassword(Long userId, String encodedPassword);

    String getPreferences(Long userId);

    boolean updatePreferences(Long userId, String preferences);

    User login(String username, String password);

    /** 增减粉丝数（delta 为 +1 或 -1） */
    void incrementFollowerCount(Long userId, int delta);

    /** 增减关注数（delta 为 +1 或 -1） */
    void incrementFollowingCount(Long userId, int delta);

    /** 递增总提交数（submit_count）；由提交链路调用。 */
    void incrementSubmitCount(Long userId);

    /** 递增通过题目数（accepted_count）；仅在首次 AC 一道题时调用。 */
    void incrementAcceptedCount(Long userId);

    /**
     * 按 submission 表全量重算某用户的 submit_count / accepted_count（T8 重判后调用）。
     * <p>口径同 Flyway V55 回填；重判改变历史 status 后增量维护无法回退，必须全量重算。</p>
     */
    void recalculateCounters(Long userId);

    /** 在当前事务中锁定所有启用管理员，供不可逆角色变更串行校验。 */
    List<Long> lockEnabledAdminIds();

    /**
     * 同步规范化 RBAC 关联：把 {@code sys_user_role} 替换为 {@code role}（1-学生/2-老师/3-管理员）对应的单条角色。
     *
     * <p>仅在 {@code oj.security.normalized-rbac=true} 时由账号写入路径调用；legacy 模式下
     * {@code user.role} 是唯一授权来源，无需写关联（保持历史库零改动）。角色值非法时返回 false。</p>
     */
    boolean syncNormalizedRole(Long userId, Byte role);
}
