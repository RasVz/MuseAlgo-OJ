package com.musealgo.oj.system.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** Reads and maintains normalized RBAC grants (sys_user_role) when {@code oj.security.normalized-rbac} is enabled. */
@Mapper
public interface UserAuthorityMapper {

    @Select("""
            SELECT DISTINCT CONCAT('ROLE_', r.code)
            FROM sys_user_role ur
            INNER JOIN sys_role r ON r.id = ur.role_id
            WHERE ur.user_id = #{userId}
            UNION
            SELECT DISTINCT p.code
            FROM sys_user_role ur
            INNER JOIN sys_role_permission rp ON rp.role_id = ur.role_id
            INNER JOIN sys_permission p ON p.id = rp.permission_id
            WHERE ur.user_id = #{userId}
            """)
    List<String> selectAuthorityCodes(@Param("userId") Long userId);

    /** 按角色 code 查 sys_role.id（内置角色 STUDENT/TEACHER/ADMIN 由 V63 保证存在）。 */
    @Select("SELECT id FROM sys_role WHERE code = #{code} LIMIT 1")
    Byte selectRoleIdByCode(@Param("code") String code);

    /** 清空用户的规范化角色关联（撤权/改角色前调用）。 */
    @Delete("DELETE FROM sys_user_role WHERE user_id = #{userId}")
    int deleteUserRoles(@Param("userId") Long userId);

    /** 写入单条 sys_user_role 关联；调用方须先 deleteUserRoles，保证一个用户只保留当前角色。 */
    @Insert("INSERT INTO sys_user_role (user_id, role_id) VALUES (#{userId}, #{roleId})")
    int insertUserRole(@Param("userId") Long userId, @Param("roleId") Byte roleId);
}
