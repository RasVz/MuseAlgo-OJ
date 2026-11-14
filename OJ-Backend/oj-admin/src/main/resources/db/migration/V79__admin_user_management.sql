-- ============================================================
-- 管理端用户管理：封禁原因/到期时间，以及筛选与排序索引
-- ============================================================

ALTER TABLE `user`
    ADD COLUMN `ban_reason` VARCHAR(255) NULL COMMENT '封禁原因' AFTER `is_banned`,
    ADD COLUMN `ban_expire_time` DATETIME NULL COMMENT '封禁到期时间；NULL 表示永久封禁' AFTER `ban_reason`;

ALTER TABLE `user`
    ADD INDEX `idx_user_role_banned` (`role`, `is_banned`),
    ADD INDEX `idx_user_ban_expire` (`is_banned`, `ban_expire_time`),
    ADD INDEX `idx_user_create_time` (`create_time`),
    ADD INDEX `idx_user_last_login_time` (`last_login_time`);
