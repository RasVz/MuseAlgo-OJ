package com.musealgo.oj.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.stereotype.Service;
import com.musealgo.oj.api.report.ReportActorSnapshot;
import com.musealgo.oj.system.entity.LoginUser;
import com.musealgo.oj.system.entity.User;
import com.musealgo.oj.system.mapper.UserMapper;
import com.musealgo.oj.system.mapper.UserAuthorityMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spring Security 用于加载用户详情的实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserMapper userMapper;

    private final UserAuthorityMapper userAuthorityMapper;

    @Value("${oj.security.normalized-rbac:false}")
    private boolean normalizedRbacEnabled;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // 1. 查询用户
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username)
                .or()
                .eq(User::getEmail, username);

        User user = userMapper.selectOne(wrapper);
        if (user == null) {
            throw new UsernameNotFoundException("用户名或密码错误");
        }
        // 禁用校验：登录与每请求的 JWT 校验都会走到这里（JwtAuthenticationTokenFilter）
        if (user.getIsBanned() != null && user.getIsBanned() == 1) {
            throw new DisabledException("该账号已被禁用");
        }

        // 2. 设置权限
        List<SimpleGrantedAuthority> authorities = resolveAuthorities(user);

        // 3. 【核心修改】返回 LoginUser，而不是官方的 User
        // 这样 Authentication.getPrincipal() 拿到的就是 LoginUser 对象了
        // 因为 LoginUser 继承了 user，所以 Controller 里的 (user) 强转就能成功！
        return new LoginUser(user, authorities);
    }

    private String toRoleCode(Byte role) {
        if (role == null) {
            return "STUDENT";
        }
        return switch (role) {
            case 2 -> "TEACHER";
            case 3 -> "ADMIN";
            default -> "STUDENT";
        };
    }

    private List<SimpleGrantedAuthority> resolveAuthorities(User user) {
        if (normalizedRbacEnabled) {
            // 规范化模式下授权唯一来源是 sys_user_role → sys_role_permission 关联表。
            // 关联缺失（被撤权、账号未同步映射）必须按"无任何权限"处理，禁止回退到 user.role 的
            // legacy 内置权限：否则清空关联即可被 legacy 回退重新授予 problem:manage 等权限，
            // 形成撤权绕过；也会把 user.role=3 的缺映射账号重新解释为管理员。
            // fail-closed 语义是"授权为空"（登录仍成功，受保护接口全部 403），不是 401。
            List<String> codes = userAuthorityMapper.selectAuthorityCodes(user.getId());
            if (codes == null || codes.isEmpty()) {
                log.warn("normalized-rbac 已启用但用户 {} 没有任何 sys_user_role 关联，按无权限处理（fail-closed）",
                        user.getId());
                return List.of();
            }
            return codes.stream().map(SimpleGrantedAuthority::new).toList();
        }
        return legacyAuthorities(user.getRole()).stream().map(SimpleGrantedAuthority::new).toList();
    }

    /**
     * Report authorization adapter seam. It deliberately delegates to the same resolver used
     * when authenticating HTTP requests, including normalized-RBAC fail-closed behavior.
     */
    public ReportActorSnapshot reportActorSnapshot(User user) {
        if (user == null) return null;
        boolean active = (user.getIsBanned() == null || user.getIsBanned() != 1)
                && (user.getIsDeleted() == null || user.getIsDeleted() != 1);
        if (!active) return new ReportActorSnapshot(user.getId(), false, false, false);
        Set<String> authorities = resolveAuthorities(user).stream()
                .map(SimpleGrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
        return new ReportActorSnapshot(user.getId(), true,
                authorities.contains("ROLE_ADMIN"), authorities.contains("ROLE_TEACHER"));
    }

    private List<String> legacyAuthorities(Byte role) {
        String roleCode = toRoleCode(role);
        List<String> authorities = new ArrayList<>();
        authorities.add("ROLE_" + roleCode);
        if ("ADMIN".equals(roleCode)) {
            authorities.addAll(List.of(
                    "problem:manage",
                    "contest:manage",
                    "class:member:manage",
                    "submission:read:private"
            ));
        } else if ("TEACHER".equals(roleCode)) {
            authorities.addAll(List.of(
                    "problem:manage",
                    "contest:manage",
                    "class:member:manage"
            ));
        }
        return authorities;
    }
}
