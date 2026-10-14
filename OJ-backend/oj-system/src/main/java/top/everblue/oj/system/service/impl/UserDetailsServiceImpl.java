package top.everblue.oj.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.stereotype.Service;
import top.everblue.oj.system.entity.LoginUser;
import top.everblue.oj.system.entity.User;
import top.everblue.oj.system.mapper.UserMapper;
import top.everblue.oj.system.mapper.UserAuthorityMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Security 用于加载用户详情的实现类
 */
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
            List<String> codes = userAuthorityMapper.selectAuthorityCodes(user.getId());
            if (codes != null && !codes.isEmpty()) {
                return codes.stream().map(SimpleGrantedAuthority::new).toList();
            }
        }
        return legacyAuthorities(user.getRole()).stream().map(SimpleGrantedAuthority::new).toList();
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
