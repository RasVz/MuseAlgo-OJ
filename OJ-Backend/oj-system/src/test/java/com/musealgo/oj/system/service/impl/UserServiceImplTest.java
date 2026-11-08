package com.musealgo.oj.system.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import com.musealgo.oj.system.mapper.UserAuthorityMapper;
import com.musealgo.oj.system.mapper.UserMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UserServiceImpl} 单测：计数重算（T8）+ 规范化 RBAC 关联同步（SEC-01）。
 * <p>
 * seam：
 * <ul>
 *   <li>计数方法必须把 userId 原样交给 UserMapper 的全量重算 SQL；重算口径与幂等性由 SQL 保证。</li>
 *   <li>{@code syncNormalizedRole} 只做“删旧关联 + 插当前角色关联”，角色 code → id 的映射交给
 *       UserAuthorityMapper（V63 保证内置角色存在），非法角色/缺角色时不写任何关联。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private UserMapper userMapper;
    @Mock
    private UserAuthorityMapper userAuthorityMapper;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(passwordEncoder, userAuthorityMapper);
        // ServiceImpl#baseMapper 由 MyBatis-Plus 在容器里注入，单测手工塞入 mock
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
    }

    @Test
    void recalculateCountersPassesUserIdToMapper() {
        userService.recalculateCounters(7L);

        verify(userMapper).recalculateCounters(7L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"profile", "preferences"})
    void accountSettingsWritesDoNotRollBackConcurrentSecurityChanges(String operation) {
        var live = new com.musealgo.oj.system.entity.User();
        live.setId(12L);
        live.setPassword("old-hash");
        live.setCredentialVersion(4L);
        live.setRole((byte) 2);
        live.setIsBanned((byte) 0);
        when(userMapper.selectById(12L)).thenAnswer(invocation -> {
            var selected = new com.musealgo.oj.system.entity.User();
            org.springframework.beans.BeanUtils.copyProperties(live, selected);
            // Apply an administrator reset between the SELECT and settings write.
            live.setPassword("administrator-reset-hash");
            live.setCredentialVersion(5L);
            live.setRole((byte) 1);
            live.setIsBanned((byte) 1);
            return selected;
        });
        when(userMapper.updateById(any(com.musealgo.oj.system.entity.User.class))).thenAnswer(invocation -> {
            com.musealgo.oj.system.entity.User patch = invocation.getArgument(0);
            // Model MyBatis' non-null field update at the repository boundary.
            if (patch.getPassword() != null) live.setPassword(patch.getPassword());
            if (patch.getCredentialVersion() != null) live.setCredentialVersion(patch.getCredentialVersion());
            if (patch.getRole() != null) live.setRole(patch.getRole());
            if (patch.getIsBanned() != null) live.setIsBanned(patch.getIsBanned());
            if (patch.getNickname() != null) live.setNickname(patch.getNickname());
            if (patch.getPreferences() != null) live.setPreferences(patch.getPreferences());
            return 1;
        });

        if (operation.equals("profile")) {
            var profile = new com.musealgo.oj.system.entity.User();
            profile.setNickname("New display name");
            org.junit.jupiter.api.Assertions.assertTrue(userService.updateProfile(12L, profile));
            org.junit.jupiter.api.Assertions.assertEquals("New display name", live.getNickname());
        } else {
            org.junit.jupiter.api.Assertions.assertTrue(userService.updatePreferences(12L, "{\"language\":\"java\"}"));
            org.junit.jupiter.api.Assertions.assertEquals("{\"language\":\"java\"}", live.getPreferences());
        }
        org.junit.jupiter.api.Assertions.assertAll(
                () -> org.junit.jupiter.api.Assertions.assertEquals("administrator-reset-hash", live.getPassword()),
                () -> org.junit.jupiter.api.Assertions.assertEquals(5L, live.getCredentialVersion()),
                () -> org.junit.jupiter.api.Assertions.assertEquals((byte) 1, live.getRole()),
                () -> org.junit.jupiter.api.Assertions.assertEquals((byte) 1, live.getIsBanned()));
    }

    @Test
    void emptySettingsAreSuccessfulNoOpsWithoutRewritingTheAccount() {
        when(userMapper.selectById(12L)).thenReturn(new com.musealgo.oj.system.entity.User());
        org.junit.jupiter.api.Assertions.assertTrue(userService.updateProfile(12L,
                new com.musealgo.oj.system.entity.User()));
        org.junit.jupiter.api.Assertions.assertTrue(userService.updatePreferences(12L, null));
        verify(userMapper, never()).updateById(any(com.musealgo.oj.system.entity.User.class));
    }

    @Test
    void registrationCannotChooseThePrimaryKeyOrInventProgress() {
        var request = new com.musealgo.oj.system.entity.User();
        request.setId(999L);
        request.setUsername("synthetic-user");
        request.setNickname("Synthetic");
        request.setPassword("synthetic-password");
        request.setRole((byte) 3);
        request.setRating(999999);
        request.setAcceptedCount(999999);
        when(passwordEncoder.encode("synthetic-password")).thenReturn("encoded-fixture");
        when(userMapper.insert(any(com.musealgo.oj.system.entity.User.class))).thenAnswer(invocation -> {
            com.musealgo.oj.system.entity.User row = invocation.getArgument(0);
            org.junit.jupiter.api.Assertions.assertAll(
                    () -> org.junit.jupiter.api.Assertions.assertNull(row.getId()),
                    () -> org.junit.jupiter.api.Assertions.assertEquals((byte) 1, row.getRole()),
                    () -> org.junit.jupiter.api.Assertions.assertNull(row.getRating()),
                    () -> org.junit.jupiter.api.Assertions.assertNull(row.getAcceptedCount()),
                    () -> org.junit.jupiter.api.Assertions.assertEquals("synthetic-user", row.getUsername()),
                    () -> org.junit.jupiter.api.Assertions.assertEquals("encoded-fixture", row.getPassword()));
            row.setId(42L);
            return 1;
        });
        org.junit.jupiter.api.Assertions.assertTrue(userService.register(request));
    }

    @Test
    void changePasswordAtomicallyUpdatesHashAndCredentialVersion() {
        com.musealgo.oj.system.entity.User current = new com.musealgo.oj.system.entity.User();
        current.setId(12L);
        current.setPassword("old-hash");
        current.setCredentialVersion(4L);
        when(userMapper.selectById(12L)).thenReturn(current);
        when(passwordEncoder.matches("old", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");
        when(userMapper.changePasswordIfUnchanged(12L, "old-hash", "new-hash")).thenReturn(1);

        org.junit.jupiter.api.Assertions.assertTrue(userService.changePassword(12L, "old", "new-password"));

        verify(userMapper).changePasswordIfUnchanged(12L, "old-hash", "new-hash");
    }

    @Test
    void changePasswordRejectsWrongOldPasswordWithoutChangingCredentialVersion() {
        com.musealgo.oj.system.entity.User current = new com.musealgo.oj.system.entity.User();
        current.setId(12L);
        current.setPassword("old-hash");
        when(userMapper.selectById(12L)).thenReturn(current);
        when(passwordEncoder.matches("wrong", "old-hash")).thenReturn(false);

        org.junit.jupiter.api.Assertions.assertFalse(userService.changePassword(12L, "wrong", "new-password"));

        verify(passwordEncoder, never()).encode(anyString());
        verify(userMapper, never()).updatePasswordAndIncrementCredentialVersion(anyLong(), anyString());
        verify(userMapper, never()).changePasswordIfUnchanged(anyLong(), anyString(), anyString());
    }

    @Test
    void changePasswordDoesNotOverwriteConcurrentAdministratorReset() {
        com.musealgo.oj.system.entity.User current = new com.musealgo.oj.system.entity.User();
        current.setId(12L);
        current.setPassword("old-hash");
        when(userMapper.selectById(12L)).thenReturn(current);
        when(passwordEncoder.matches("old", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");
        when(userMapper.changePasswordIfUnchanged(12L, "old-hash", "new-hash")).thenReturn(0);

        org.junit.jupiter.api.Assertions.assertFalse(userService.changePassword(12L, "old", "new-password"));

        verify(userMapper).changePasswordIfUnchanged(12L, "old-hash", "new-hash");
    }

    @Test
    void updatePasswordDelegatesToAtomicMapperUpdate() {
        when(userMapper.updatePasswordAndIncrementCredentialVersion(13L, "encoded-reset")).thenReturn(1);

        org.junit.jupiter.api.Assertions.assertTrue(userService.updatePassword(13L, "encoded-reset"));

        verify(userMapper).updatePasswordAndIncrementCredentialVersion(13L, "encoded-reset");
    }

    @Test
    void incrementSubmitCountUsesAtomicIncrement() {
        userService.incrementSubmitCount(7L);

        verify(userMapper).update(org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any());
    }

    // ===== SEC-01：规范化 RBAC 关联同步 =====

    @Test
    void syncNormalizedRoleReplacesExistingGrantForAdmin() {
        when(userAuthorityMapper.selectRoleIdByCode("ADMIN")).thenReturn((byte) 3);

        boolean ok = userService.syncNormalizedRole(42L, (byte) 3);

        org.junit.jupiter.api.Assertions.assertTrue(ok);
        // 先清空旧关联，再写入当前角色，保证一个用户只保留一条规范化角色
        verify(userAuthorityMapper).deleteUserRoles(42L);
        verify(userAuthorityMapper).insertUserRole(42L, (byte) 3);
    }

    @Test
    void syncNormalizedRoleMapsStudentAndTeacher() {
        when(userAuthorityMapper.selectRoleIdByCode("STUDENT")).thenReturn((byte) 1);
        when(userAuthorityMapper.selectRoleIdByCode("TEACHER")).thenReturn((byte) 2);

        userService.syncNormalizedRole(7L, (byte) 1);
        userService.syncNormalizedRole(8L, (byte) 2);

        verify(userAuthorityMapper).deleteUserRoles(7L);
        verify(userAuthorityMapper).insertUserRole(7L, (byte) 1);
        verify(userAuthorityMapper).deleteUserRoles(8L);
        verify(userAuthorityMapper).insertUserRole(8L, (byte) 2);
    }

    @Test
    void syncNormalizedRoleRejectsIllegalRole() {
        boolean ok = userService.syncNormalizedRole(9L, (byte) 9);

        org.junit.jupiter.api.Assertions.assertFalse(ok);
        verify(userAuthorityMapper, never()).deleteUserRoles(anyLong());
        verify(userAuthorityMapper, never()).insertUserRole(anyLong(), any());
    }

    @Test
    void syncNormalizedRoleFailsClosedWhenReferenceRoleMissing() {
        // V63 负责内置角色，但若库未按参考数据初始化，必须失败而不是写出错误关联
        when(userAuthorityMapper.selectRoleIdByCode("ADMIN")).thenReturn(null);

        boolean ok = userService.syncNormalizedRole(42L, (byte) 3);

        org.junit.jupiter.api.Assertions.assertFalse(ok);
        verify(userAuthorityMapper, never()).deleteUserRoles(anyLong());
        verify(userAuthorityMapper, never()).insertUserRole(anyLong(), any());
    }

    @Test
    void syncNormalizedRoleRoundTripUsesSingleAssociation() {
        when(userAuthorityMapper.selectRoleIdByCode("TEACHER")).thenReturn((byte) 2);

        userService.syncNormalizedRole(5L, (byte) 2);

        // 撤权/改角色语义：delete 先于 insert，且只插一条
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(userAuthorityMapper);
        inOrder.verify(userAuthorityMapper).deleteUserRoles(5L);
        inOrder.verify(userAuthorityMapper).insertUserRole(5L, (byte) 2);
    }
}
