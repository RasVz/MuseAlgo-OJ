package top.everblue.oj.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.everblue.oj.system.entity.AuditLog;

/**
 * 审计日志 Mapper —— 仅使用 MyBatis-Plus 内置的 insert 能力，无需自定义 SQL。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
