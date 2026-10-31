-- ============================================================
-- 用户管理增强：记录最后一次登录时间
-- ============================================================

ALTER TABLE user
    ADD COLUMN last_login_time DATETIME NULL COMMENT '最后一次登录时间' AFTER update_time;
