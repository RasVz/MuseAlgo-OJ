package top.everblue.oj.system.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** Reads normalized RBAC grants after the V1 migration is enabled. */
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
}
