package com.musealgo.oj.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.musealgo.oj.system.entity.User;
import com.musealgo.oj.system.mapper.UserAuthorityMapper;
import com.musealgo.oj.system.mapper.UserMapper;
import com.musealgo.oj.system.service.UserService;

import java.util.List;

/**
 * <p>
 * 用户表 服务实现类
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final PasswordEncoder passwordEncoder;

    private final UserAuthorityMapper userAuthorityMapper;

    @Value("${oj.security.normalized-rbac:false}")
    private boolean normalizedRbacEnabled;

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
    @Transactional(rollbackFor = Exception.class)
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
        if (!this.save(user)) {
            return false;
        }
        // 4. 规范化 RBAC 模式下同步角色关联；否则新账号在 sys_user_role 无映射，
        //    会被 fail-closed 判为无权限（而不是被 legacy 回退兜底）。
        //    关联写不进去说明参考数据缺失/库异常，直接回滚注册，避免产生无权限的孤儿账号。
        if (normalizedRbacEnabled && !syncNormalizedRole(user.getId(), user.getRole())) {
            throw new IllegalStateException("注册失败：规范化权限关联未能建立");
        }
        return true;
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
    public void incrementSubmitCount(Long userId) {
        // COALESCE 兼容老数据 NULL；单条 UPDATE 原子自增，避免读改写丢更新
        baseMapper.update(null, new UpdateWrapper<User>()
                .setSql("submit_count = COALESCE(submit_count, 0) + 1")
                .eq("id", userId));
    }

    @Override
    public void incrementAcceptedCount(Long userId) {
        baseMapper.update(null, new UpdateWrapper<User>()
                .setSql("accepted_count = COALESCE(accepted_count, 0) + 1")
                .eq("id", userId));
    }

    @Override
    public void recalculateCounters(Long userId) {
        // 重判后派生计数按 submission 全量重算：口径与 V55 回填一致，幂等；
        // 不走 increment* 的增量路径——旧结果被改判后增量无法回退。
        baseMapper.recalculateCounters(userId);
    }

    @Override
    public List<Long> lockEnabledAdminIds() {
        return baseMapper.lockEnabledAdminIds();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean syncNormalizedRole(Long userId, Byte role) {
        if (userId == null || role == null) {
            log.warn("同步规范化 RBAC 关联被跳过：userId={} role={}", userId, role);
            return false;
        }
        String roleCode;
        if (role == 1) {
            roleCode = "STUDENT";
        } else if (role == 2) {
            roleCode = "TEACHER";
        } else if (role == 3) {
            roleCode = "ADMIN";
        } else {
            log.warn("同步规范化 RBAC 关联失败：非法角色值 {}（用户 {}）", role, userId);
            return false;
        }
        Byte roleId = userAuthorityMapper.selectRoleIdByCode(roleCode);
        if (roleId == null) {
            // V63 负责保证内置角色存在；缺失说明库未按参考数据初始化。
            log.error("同步规范化 RBAC 关联失败：sys_role 缺少角色 {}，用户 {} 的关联未写入", roleCode, userId);
            return false;
        }
        userAuthorityMapper.deleteUserRoles(userId);
        userAuthorityMapper.insertUserRole(userId, roleId);
        return true;
    }
}
