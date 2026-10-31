package com.musealgo.oj.system.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import com.musealgo.oj.system.mapper.UserAuthorityMapper;
import com.musealgo.oj.system.mapper.UserMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
