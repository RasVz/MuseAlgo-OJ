-- Table: audit_log
-- Database: oj_db
-- Source: 实体类 AuditLog.java (oj-system 模块)
-- Note: 仅有实体类定义，无 Flyway 迁移脚本，DDL 根据实体类字段推导

CREATE TABLE IF NOT EXISTS `audit_log` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`         BIGINT       NOT NULL              COMMENT '操作人用户 ID',
  `username`        VARCHAR(64)  NOT NULL              COMMENT '操作人用户名（冗余）',
  `module`          VARCHAR(64)  NOT NULL              COMMENT '操作模块（如 user/problem/contest）',
  `action`          VARCHAR(64)  NOT NULL              COMMENT '操作动作（如 delete_user/reset_password）',
  `method_name`     VARCHAR(255) NOT NULL              COMMENT '执行的方法全限定名（类名.方法名）',
  `request_params`  TEXT         NULL                  COMMENT '方法入参（JSON 格式，超长自动截断）',
  `client_ip`       VARCHAR(45)  NULL                  COMMENT '请求来源 IP',
  `execution_time`  BIGINT       NULL                  COMMENT '方法执行耗时（毫秒）',
  `status`          TINYINT      NOT NULL DEFAULT 1    COMMENT '执行结果：1-成功 0-异常',
  `error_message`   TEXT         NULL                  COMMENT '异常信息（失败时记录）',
  `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '日志创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_audit_user` (`user_id`),
  KEY `idx_audit_module` (`module`),
  KEY `idx_audit_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作审计日志表';
