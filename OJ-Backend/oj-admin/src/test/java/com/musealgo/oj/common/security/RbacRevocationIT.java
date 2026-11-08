package com.musealgo.oj.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;
import com.musealgo.oj.system.service.impl.UserDetailsServiceImpl;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-01：真实 MySQL 下的撤权语义 IT（隔离栈）。
 *
 * 覆盖：
 * 1) normalized-rbac=true 时授权唯一来源是 sys_user_role 关联表；清空关联后授权为空
 *    （fail-closed），不得回退到 user.role 的 legacy 语义而恢复权限。
 * 2) 关联为空时登录仍然成功（不抛 UsernameNotFoundException），只是没有任何授权；
 *    受保护接口由此得到 403（空授权）而不是 401（未认证）。
 * 3) role=3 但缺映射的账号不得因此获得 ROLE_ADMIN（缺映射不等于管理员）。
 * 4) legacy 模式（normalized-rbac=false）完全忽略 sys_user_role，按 user.role 派生授权。
 *
 * 说明：legacy 语义用 ReflectionTestUtils 切换同一上下文中的 normalizedRbacEnabled 字段，
 * 避免为两种模式各起一个 Spring 上下文（8GB 机器与门禁窗口资源受限）。断言仍走真实的
 * UserDetailsServiceImpl → MyBatis → MySQL 链路（关联表读写都是真实 SQL）。
 */
@SpringBootTest(properties = {
        "oj.messaging.rabbitmq-enabled=false",
        "oj.judge.worker.enabled=false",
        "oj.security.normalized-rbac=true"
})
class RbacRevocationIT {

    private static final String USERNAME_PREFIX = "codex_sec01_";

    @Autowired
    private UserDetailsServiceImpl userDetailsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long studentUserId;
    private Long teacherUserId;
    private Long adminRoleUserId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        studentUserId = insertUser(USERNAME_PREFIX + "s_" + suffix, (byte) 1);
        teacherUserId = insertUser(USERNAME_PREFIX + "t_" + suffix, (byte) 2);
        adminRoleUserId = insertUser(USERNAME_PREFIX + "a_" + suffix, (byte) 3);
        // 初始都给正确关联：学生→STUDENT(1)，教师→TEACHER(2)，管理员角色→ADMIN(3)
        insertUserRole(studentUserId, 1L);
        insertUserRole(teacherUserId, 2L);
        insertUserRole(adminRoleUserId, 3L);
        setNormalized(true);
    }

    @AfterEach
    void tearDown() {
        setNormalized(true);
        for (Long userId : List.of(studentUserId, teacherUserId, adminRoleUserId)) {
            if (userId != null) {
                jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
                jdbcTemplate.update("DELETE FROM user WHERE id = ?", userId);
            }
        }
    }

    @Test
    @DisplayName("normalized：有 sys_user_role 关联时授权来自关联表（ROLE_* + 权限码）")
    void normalizedUsesAssociationAsOnlySource() {
        Set<String> teacherAuthorities = authorities(load(teacherUserId));
        assertTrue(teacherAuthorities.contains("ROLE_TEACHER"), "关联 role_id=2 应派生 ROLE_TEACHER");
        assertTrue(teacherAuthorities.contains("problem:manage"),
                "TEACHER 关联应带出 sys_role_permission 中的 problem:manage，实际=" + teacherAuthorities);
        assertFalse(teacherAuthorities.contains("ROLE_ADMIN"));

        Set<String> studentAuthorities = authorities(load(studentUserId));
        assertTrue(studentAuthorities.contains("ROLE_STUDENT"));
        assertFalse(studentAuthorities.contains("ROLE_TEACHER"));
    }

    @Test
    @DisplayName("normalized：清空 sys_user_role 后该用户授权为空（fail-closed，不是回退 legacy）")
    void normalizedEmptyAssociationIsFailClosed() {
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", teacherUserId);

        UserDetails details = load(teacherUserId);
        assertNotNull(details, "缺映射不应导致 loadUserByUsername 抛异常：登录仍成功，只是无授权");
        Set<String> authorities = authorities(details);
        assertTrue(authorities.isEmpty(),
                "normalized 下缺关联必须 fail-closed 返回空授权，不得用 user.role=2 回退成 TEACHER，实际=" + authorities);
    }

    @Test
    @DisplayName("normalized：role=3 但缺映射不得被当作管理员（缺映射≠管理员）")
    void normalizedAdminRoleWithoutMappingIsNotAdmin() {
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", adminRoleUserId);

        Set<String> authorities = authorities(load(adminRoleUserId));
        assertFalse(authorities.contains("ROLE_ADMIN"),
                "user.role=3 但 sys_user_role 为空时不得获得 ROLE_ADMIN，实际=" + authorities);
        assertTrue(authorities.isEmpty(), "缺映射必须整体空授权，实际=" + authorities);
    }

    @Test
    @DisplayName("normalized：撤权即时生效（重新加载同一用户即失去权限），恢复关联后立即恢复")
    void normalizedRevocationTakesEffectImmediately() {
        assertTrue(authorities(load(teacherUserId)).contains("problem:manage"));

        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", teacherUserId);
        assertTrue(authorities(load(teacherUserId)).isEmpty(), "撤权后授权应立即为空");

        insertUserRole(teacherUserId, 2L);
        assertTrue(authorities(load(teacherUserId)).contains("problem:manage"),
                "恢复关联后授权应立即恢复（不做进程内缓存）");
    }

    @Test
    @DisplayName("legacy：完全不查 sys_user_role，按 user.role 派生授权")
    void legacyIgnoresAssociationTable() {
        // 清空关联后再切 legacy：若 legacy 仍然拿到 TEACHER 权限，说明它不依赖关联表
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", teacherUserId);
        setNormalized(false);
        try {
            Set<String> teacherAuthorities = authorities(load(teacherUserId));
            assertTrue(teacherAuthorities.contains("ROLE_TEACHER"), "legacy 应按 user.role=2 派生 ROLE_TEACHER");
            assertTrue(teacherAuthorities.contains("problem:manage"), "legacy TEACHER 应带内置 problem:manage");
            assertTrue(teacherAuthorities.contains("contest:manage"));
            assertTrue(teacherAuthorities.contains("class:member:manage"));

            // role=1 → 仅 ROLE_STUDENT，无内置管理权限
            Set<String> studentAuthorities = authorities(load(studentUserId));
            assertEquals(Set.of("ROLE_STUDENT"), studentAuthorities);
        } finally {
            setNormalized(true);
        }
    }

    private UserDetails load(Long userId) {
        String username = jdbcTemplate.queryForObject(
                "SELECT username FROM user WHERE id = ?", String.class, userId);
        return userDetailsService.loadUserByUsername(username);
    }

    private Set<String> authorities(UserDetails details) {
        return details.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    private void setNormalized(boolean enabled) {
        ReflectionTestUtils.setField(userDetailsService, "normalizedRbacEnabled", enabled);
    }

    private Long insertUser(String username, byte role) {
        jdbcTemplate.update(
                "INSERT INTO user(username, password, nickname, role) VALUES (?, 'test-only', ?, ?)",
                username, username, role);
        return jdbcTemplate.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }

    private void insertUserRole(Long userId, Long roleId) {
        jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);
    }
}
