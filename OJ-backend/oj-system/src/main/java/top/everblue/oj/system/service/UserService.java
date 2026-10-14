package top.everblue.oj.system.service;

import top.everblue.oj.system.entity.User;
import com.baomidou.mybatisplus.extension.service.IService;

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

    String getPreferences(Long userId);

    boolean updatePreferences(Long userId, String preferences);

    User login(String username, String password);

    /** 增减粉丝数（delta 为 +1 或 -1） */
    void incrementFollowerCount(Long userId, int delta);

    /** 增减关注数（delta 为 +1 或 -1） */
    void incrementFollowingCount(Long userId, int delta);

    /** 在当前事务中锁定所有启用管理员，供不可逆角色变更串行校验。 */
    List<Long> lockEnabledAdminIds();
}
