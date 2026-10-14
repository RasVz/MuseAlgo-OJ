-- ==========================================
-- 审计日志表 (audit_log)
-- 用于记录管理员等敏感操作的操作流水
-- ==========================================
CREATE TABLE IF NOT EXISTS `audit_log` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`       BIGINT       DEFAULT NULL              COMMENT '操作人ID（未登录为null）',
    `username`      VARCHAR(64)  DEFAULT NULL              COMMENT '操作人用户名（冗余字段，方便直接查看）',
    `module`        VARCHAR(32)  NOT NULL                  COMMENT '操作模块（如 user/problem/contest）',
    `action`        VARCHAR(64)  NOT NULL                  COMMENT '操作动作（如 delete_user/reset_password）',
    `method_name`   VARCHAR(255) DEFAULT NULL              COMMENT '执行的方法全限定名',
    `request_params` TEXT        DEFAULT NULL              COMMENT '方法入参（JSON格式，超出长度截断）',
    `client_ip`     VARCHAR(45)  DEFAULT NULL              COMMENT '请求来源IP（支持IPv6长度）',
    `execution_time` BIGINT      DEFAULT 0                 COMMENT '方法执行耗时（毫秒）',
    `status`        TINYINT      NOT NULL DEFAULT 1        COMMENT '执行结果：1-成功 0-异常',
    `error_message` VARCHAR(512) DEFAULT NULL              COMMENT '异常信息（失败时记录）',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '日志创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_user_id`    (`user_id`),
    INDEX `idx_module`     (`module`),
    INDEX `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志表';
