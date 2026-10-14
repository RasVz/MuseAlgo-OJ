-- ============================================================
-- 管理员功能支撑：客观题解析、用户禁用
-- ============================================================

-- 1) 客观题解析：判分后展示给学生的订正说明
ALTER TABLE exam_question
    ADD COLUMN explanation TEXT NULL COMMENT '题目解析（判分后展示）' AFTER answer;

-- 2) 用户禁用：登录与所有请求被拒绝（JWT 过滤器每请求校验）
ALTER TABLE user
    ADD COLUMN is_banned TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1=禁用' AFTER role;
