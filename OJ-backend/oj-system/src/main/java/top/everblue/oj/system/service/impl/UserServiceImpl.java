package top.everblue.oj.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import top.everblue.oj.system.entity.User;
import top.everblue.oj.system.mapper.UserMapper;
import top.everblue.oj.system.service.UserService;

import java.util.List;

/**
 * <p>
 * 用户表 服务实现类
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final PasswordEncoder passwordEncoder;

    @Override
    public Integer registerCheck(User user) {
        String username = user.getUsername();
        String email = user.getEmail();

        // 判断用户名唯一性
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        long count = this.count(wrapper);
        if(count > 0){
            return 1;
        }

        // 判断邮箱唯一性
        wrapper.clear();
        wrapper.eq(User::getEmail, email);
        count = this.count(wrapper);
        if(count > 0){
            return 2;
        }

        // 密码强度规则由前端校验，服务端只负责账号与邮箱唯一性

        return 0;
    }

    @Override
    public boolean register(User user) {
        // 1. 密码加密 (这一步你已经写了)
        String encodedPassword = passwordEncoder.encode(user.getPassword());
        user.setPassword(encodedPassword);

        // 2. 设置默认信息
        // 默认角色：1-学生
        user.setRole((byte) 1);

        // 默认头像 (可选，可以先给个默认图，或者留空)
        // user.setAvatar("https://默认头像地址...");

        // 3. 最终落库 (调用 MyBatis-Plus 的 save 方法)
        return this.save(user);
    }

    @Override
    public boolean updateProfile(Long userId, User user) {
        User dbUser = this.getById(userId);
        if (dbUser == null) {
            return false;
        }

        dbUser.setNickname(user.getNickname());
        dbUser.setRealName(user.getRealName());
        dbUser.setAvatar(user.getAvatar());
        dbUser.setMotto(user.getMotto());
        dbUser.setSchool(user.getSchool());
        dbUser.setMajor(user.getMajor());
        // 邮箱绑定（当前为壳子：仅保存，不发送验证邮件）
        if (user.getEmail() != null && !user.getEmail().trim().isEmpty()) {
            dbUser.setEmail(user.getEmail().trim());
        }

        return this.updateById(dbUser);
    }

    @Override
    public boolean changePassword(Long userId, String oldPassword, String newPassword) {
        User dbUser = this.getById(userId);
        if (dbUser == null || dbUser.getPassword() == null) {
            return false;
        }

        if (!passwordEncoder.matches(oldPassword, dbUser.getPassword())) {
            return false;
        }

        dbUser.setPassword(passwordEncoder.encode(newPassword));
        return this.updateById(dbUser);
    }

    @Override
    public String getPreferences(Long userId) {
        User dbUser = this.getById(userId);
        return dbUser == null ? null : dbUser.getPreferences();
    }

    @Override
    public boolean updatePreferences(Long userId, String preferences) {
        User dbUser = this.getById(userId);
        if (dbUser == null) {
            return false;
        }

        dbUser.setPreferences(preferences);
        return this.updateById(dbUser);
    }

    @Override
    public User login(String username, String password) {
        // 1. 根据用户名查询用户
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();

        wrapper.and(w -> w.eq(User::getUsername, username)
                .or()
                .eq(User::getEmail, username)); // 这里的 username 变量其实是用户输入的字符串

        User user = this.getOne(wrapper);

        // 2. 如果用户不存在
        if (user == null) {
            return null;
        }

        // 3. 比对密码 (rawPassword, encodedPassword)
        // 注意：数据库里的密码是加密后的，必须用 matches 方法
        if (!passwordEncoder.matches(password, user.getPassword())) {
            return null;
        }

        // 4. 登录成功，清除密码敏感信息再返回
        user.setPassword(null);
        return user;
    }

    @Override
    public void incrementFollowerCount(Long userId, int delta) {
        baseMapper.update(null, new UpdateWrapper<User>()
                .setSql("follower_count = follower_count + " + delta)
                .eq("id", userId));
    }

    @Override
    public void incrementFollowingCount(Long userId, int delta) {
        baseMapper.update(null, new UpdateWrapper<User>()
                .setSql("following_count = following_count + " + delta)
                .eq("id", userId));
    }

    @Override
    public List<Long> lockEnabledAdminIds() {
        return baseMapper.lockEnabledAdminIds();
    }
}
