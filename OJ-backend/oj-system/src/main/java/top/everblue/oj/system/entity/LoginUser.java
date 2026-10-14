package top.everblue.oj.system.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import top.everblue.oj.common.security.AuthenticatedUser;

import java.util.Collection;

/**
 * 核心桥梁类：
 * 既包含数据库实体 User 的所有字段 (ID, 昵称, 头像等)，
 * 又满足 Spring Security 对 UserDetails 的要求。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class LoginUser extends User implements UserDetails, AuthenticatedUser {

    private Collection<? extends GrantedAuthority> authorities;

    // 构造函数：把 User 的值拷过来
    public LoginUser(User User, Collection<? extends GrantedAuthority> authorities) {
        if (User != null) {
            BeanUtils.copyProperties(User, this);
        }
        this.authorities = authorities;
    }

    // === 下面是 UserDetails 必须实现的方法 ===

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return true; }
}