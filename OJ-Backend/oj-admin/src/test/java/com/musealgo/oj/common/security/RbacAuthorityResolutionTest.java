package com.musealgo.oj.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import com.musealgo.oj.system.entity.LoginUser;
import com.musealgo.oj.system.entity.User;
import com.musealgo.oj.system.mapper.UserAuthorityMapper;
import com.musealgo.oj.system.mapper.UserMapper;
import com.musealgo.oj.system.service.impl.UserDetailsServiceImpl;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEC-01：权限解析链单测——legacy（默认）与 normalized（规范化）两种模式的行为契约。
 *
 * <p>被测 seam：{@link UserDetailsServiceImpl#loadUserByUsername(String)} 返回的 authorities 集合。
 * 这是所有 {@code @PreAuthorize}、{@code SecurityUtils.isAdmin()/isTeacherOrAdmin()} 的唯一数据来源。</p>
 *
 * <p>核心契约：</p>
 * <ul>
 *   <li>legacy（{@code oj.security.normalized-rbac=false}，默认）：只按 {@code user.role} 派生
 *       {@code ROLE_*} 与内置权限码；不查询关联表。</li>
 *   <li>normalized=true：授权唯一来源是 {@code sys_user_role → sys_role_permission}；关联为空时
 *       <b>fail-closed 返回空授权</b>，绝不回退 legacy（否则清空关联即可恢复 user.role 的权限，
 *       构成撤权绕过，并把缺映射的 role=3 账号重新解释为管理员）。</li>
 *   <li>fail-closed 语义是“登录成功但无任何权限”，受保护接口随后 403，不是抛 401。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RbacAuthorityResolutionTest {

    private static final String USERNAME = "sec01_user";

    @Mock
    private UserMapper userMapper;
    @Mock
    private UserAuthorityMapper userAuthorityMapper;

    private UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    void setUp() {
        userDetailsService = new UserDetailsServiceImpl(userMapper, userAuthorityMapper);
    }

    // ===== legacy 模式（默认值，保持现状） =====

    @Test
    void legacyAdminKeepsRoleAuthoritiesAndBuiltInPermissions() {
        givenUser(role((byte) 3));
        setNormalized(false);

        List<String> authorities = loadAuthorities();

        assertTrue(authorities.contains("ROLE_ADMIN"), "legacy 管理员必须保留 ROLE_ADMIN");
        assertTrue(authorities.containsAll(List.of(
                "problem:manage", "contest:manage", "class:member:manage", "submission:read:private")));
        verify(userAuthorityMapper, never()).selectAuthorityCodes(any());
    }

    @Test
    void legacyStudentOnlyHasRoleAuthority() {
        givenUser(role((byte) 1));
        setNormalized(false);

        List<String> authorities = loadAuthorities();

        assertEquals(List.of("ROLE_STUDENT"), authorities);
        verify(userAuthorityMapper, never()).selectAuthorityCodes(any());
    }

    @Test
    void legacyNullRoleFallsBackToStudent() {
        User user = user((byte) 3);
        user.setRole(null);
        givenUser(user);
        setNormalized(false);

        assertEquals(List.of("ROLE_STUDENT"), loadAuthorities());
    }

    // ===== normalized 模式（显式开启） =====

    @Test
    void normalizedUsesOnlyAssociationCodes() {
        givenUser(role((byte) 3));
        setNormalized(true);
        when(userAuthorityMapper.selectAuthorityCodes(1L))
                .thenReturn(List.of("ROLE_ADMIN", "problem:manage"));

        List<String> authorities = loadAuthorities();

        assertEquals(List.of("ROLE_ADMIN", "problem:manage"), authorities);
        // normalized 下 legacy 内置权限不再凭空追加（未在关联中授予的 submission:read:private 不出现）
        assertFalse(authorities.contains("submission:read:private"));
    }

    @Test
    void normalizedEmptyAssociationIsFailClosedEvenForAdminRole() {
        givenUser(role((byte) 3));
        setNormalized(true);
        when(userAuthorityMapper.selectAuthorityCodes(1L)).thenReturn(List.of());

        List<String> authorities = loadAuthorities();

        // 撤权绕过回归：关联被清空后必须无任何授权，而不是回退成 ROLE_ADMIN + 管理员内置权限
        assertTrue(authorities.isEmpty(), "关联为空必须 fail-closed 返回空授权，实际: " + authorities);
        assertFalse(authorities.contains("ROLE_ADMIN"), "缺映射的 role=3 不得被重新解释为管理员");
        assertFalse(authorities.contains("problem:manage"));
    }

    @Test
    void normalizedNullAssociationIsAlsoFailClosed() {
        givenUser(role((byte) 1));
        setNormalized(true);
        when(userAuthorityMapper.selectAuthorityCodes(1L)).thenReturn(null);

        assertTrue(loadAuthorities().isEmpty());
    }

    @Test
    void normalizedTeacherWithoutGrantsLosesLegacyPermissions() {
        givenUser(role((byte) 2));
        setNormalized(true);
        when(userAuthorityMapper.selectAuthorityCodes(1L)).thenReturn(List.of());

        List<String> authorities = loadAuthorities();

        assertTrue(authorities.isEmpty());
        // 不能因为是 TEACHER 就恢复 problem:manage / contest:manage / class:member:manage
        assertFalse(authorities.contains("ROLE_TEACHER"));
        assertFalse(authorities.contains("class:member:manage"));
    }

    @Test
    void normalizedStillLoadsSuccessfullyWhenNoGrants() {
        // fail-closed 授权为空 ≠ 登录失败：loadUserByUsername 仍返回 LoginUser（后续请求 403）
        givenUser(role((byte) 3));
        setNormalized(true);
        when(userAuthorityMapper.selectAuthorityCodes(1L)).thenReturn(List.of());

        LoginUser loginUser = (LoginUser) userDetailsService.loadUserByUsername(USERNAME);

        assertEquals(1L, loginUser.getId());
        assertTrue(loginUser.getAuthorities().isEmpty());
    }

    // ===== 账号状态与模式无关 =====

    @Test
    void bannedUserLoadsWithDisabledFlagForLoginCheck() {
        User banned = role((byte) 3);
        banned.setIsBanned((byte) 1);
        givenUser(banned);

        setNormalized(false);
        LoginUser legacy = (LoginUser) userDetailsService.loadUserByUsername(USERNAME);
        assertEquals((byte) 1, legacy.getIsBanned());

        setNormalized(true);
        LoginUser normalized = (LoginUser) userDetailsService.loadUserByUsername(USERNAME);
        assertEquals((byte) 1, normalized.getIsBanned());
    }

    @Test
    void missingUserIsRejected() {
        when(userMapper.selectOne(any())).thenReturn(null);

        assertThrows(org.springframework.security.core.userdetails.UsernameNotFoundException.class,
                () -> userDetailsService.loadUserByUsername(USERNAME));
    }

    // ===== helpers =====

    private void givenUser(User user) {
        lenient().when(userMapper.selectOne(any())).thenReturn(user);
    }

    private void setNormalized(boolean enabled) {
        ReflectionTestUtils.setField(userDetailsService, "normalizedRbacEnabled", enabled);
    }

    private List<String> loadAuthorities() {
        LoginUser loginUser = (LoginUser) userDetailsService.loadUserByUsername(USERNAME);
        return loginUser.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    private static User user(Byte role) {
        User user = new User();
        user.setId(1L);
        user.setUsername(USERNAME);
        user.setRole(role);
        user.setIsBanned((byte) 0);
        return user;
    }

    private static User role(byte role) {
        return user(role);
    }
}
