package top.everblue.oj.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 操作审计日志实体
 * </p>
 *
 * @author RasVz
 * @since 2025-07-12
 */
@Getter
@Setter
@TableName("audit_log")
public class AuditLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 操作人用户 ID */
    @TableField("user_id")
    private Long userId;

    /** 操作人用户名（冗余字段，方便直接查看） */
    @TableField("username")
    private String username;

    /** 操作模块（如 user / problem / contest） */
    @TableField("module")
    private String module;

    /** 操作动作（如 delete_user / reset_password） */
    @TableField("action")
    private String action;

    /** 执行的方法全限定名（类名.方法名） */
    @TableField("method_name")
    private String methodName;

    /** 方法入参（JSON 格式，超长自动截断） */
    @TableField("request_params")
    private String requestParams;

    /** 请求来源 IP */
    @TableField("client_ip")
    private String clientIp;

    /** 方法执行耗时（毫秒） */
    @TableField("execution_time")
    private Long executionTime;

    /** 执行结果：1-成功 0-异常 */
    @TableField("status")
    private Integer status;

    /** 异常信息（失败时记录） */
    @TableField("error_message")
    private String errorMessage;

    /** 日志创建时间 */
    @TableField("create_time")
    private LocalDateTime createTime;
}
