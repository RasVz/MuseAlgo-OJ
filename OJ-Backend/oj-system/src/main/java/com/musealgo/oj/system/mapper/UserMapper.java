package com.musealgo.oj.system.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.musealgo.oj.system.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

/**
 * <p>
 * 用户表 Mapper 接口
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
public interface UserMapper extends BaseMapper<User> {

    @Select("SELECT id FROM `user` WHERE role = 3 AND COALESCE(is_banned, 0) <> 1 ORDER BY id FOR UPDATE")
    List<Long> lockEnabledAdminIds();

    /**
     * 按 submission 表全量重算账号计数缓存列（T8 受控重判后的派生数据修复）。
     * <p>
     * 口径与 Flyway V55 回填完全一致（改口径前先读该迁移）：
     * <ul>
     *   <li>{@code submit_count} = 该用户全部提交数（含待判/失败提交）</li>
     *   <li>{@code accepted_count} = 该用户 AC（status=1）的不同题目数</li>
     * </ul>
     * 重判会改变历史提交的 status，增量维护无法回退旧结论，因此每次重判后必须全量重算；
     * 本语句幂等，重复执行结果一致。单条 UPDATE 完成，不需要先查询再更新。
     * 参数一律走 #{userId} 绑定，不拼 SQL。
     * </p>
     *
     * @param userId 目标用户 ID
     * @return 更新的行数（用户不存在时为 0）
     */
    @Update("UPDATE `user` SET " +
            "submit_count = (SELECT COUNT(*) FROM submission s WHERE s.user_id = #{userId}), " +
            "accepted_count = (SELECT COUNT(DISTINCT s.problem_id) FROM submission s " +
            "                  WHERE s.user_id = #{userId} AND s.status = 1) " +
            "WHERE id = #{userId}")
    int recalculateCounters(@Param("userId") Long userId);
}
