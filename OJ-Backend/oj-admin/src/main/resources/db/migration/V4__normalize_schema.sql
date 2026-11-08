-- V4: Schema normalization — fill remaining gaps after V1-V3.
-- The database is already partially normalized; this migration adds
-- only what is still missing.

-- ============================================================
-- 1. Missing UNIQUE constraints
-- ============================================================

-- user.email should be unique to prevent duplicate accounts
ALTER TABLE `user`
    ADD UNIQUE KEY `uk_email` (`email`);

-- ============================================================
-- 2. Missing indexes for frequent query patterns
-- ============================================================

-- submission: filter by contest
ALTER TABLE `submission`
    ADD INDEX `idx_contest` (`contest_id`);

-- problem: filter by author + difficulty + public
ALTER TABLE `problem`
    ADD INDEX `idx_author` (`author_id`),
    ADD INDEX `idx_public_difficulty` (`is_public`, `difficulty`);

-- post: filter by create_time desc (feed queries)
ALTER TABLE `post`
    ADD INDEX `idx_create_time` (`create_time`),
    ADD INDEX `idx_type_create` (`type`, `create_time`);

-- contest: filter by author + time range
ALTER TABLE `contest`
    ADD INDEX `idx_author` (`author_id`),
    ADD INDEX `idx_time_range` (`start_time`, `end_time`);

-- problem_list: filter by type
ALTER TABLE `problem_list`
    ADD INDEX `idx_type` (`type`);

-- user_rating: filter by contest
ALTER TABLE `user_rating`
    ADD INDEX `idx_contest` (`contest_id`);

-- user_code_draft: already has uk_user_problem_lang; add create_time index
ALTER TABLE `user_code_draft`
    ADD INDEX `idx_create_time` (`create_time`);

-- ============================================================
-- 3. Add update_time to tables that are mutable but lack it
-- ============================================================

ALTER TABLE `problem`
    ADD COLUMN `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER `create_time`;

ALTER TABLE `problem_case`
    ADD COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP AFTER `status`,
    ADD COLUMN `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER `create_time`;

ALTER TABLE `submission`
    ADD COLUMN `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER `create_time`;

ALTER TABLE `contest_user`
    ADD COLUMN `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER `create_time`;

ALTER TABLE `comment`
    ADD COLUMN `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER `create_time`;

ALTER TABLE `comment_like`
    ADD COLUMN `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER `create_time`;

ALTER TABLE `user_like`
    ADD COLUMN `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER `create_time`;

ALTER TABLE `user_rating`
    ADD COLUMN `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER `create_time`;

ALTER TABLE `user_relation`
    ADD COLUMN `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER `create_time`;

ALTER TABLE `class`
    ADD COLUMN `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER `create_time`;

ALTER TABLE `class_student`
    ADD COLUMN `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER `join_time`;

ALTER TABLE `class_courseware`
    ADD COLUMN `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER `create_time`;

-- ============================================================
-- 4. Add is_deleted to core mutable entities
-- ============================================================

-- user already has is_deleted (confirmed in DB) — skip

ALTER TABLE `problem`
    ADD COLUMN `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 AFTER `update_time`,
    ADD INDEX `idx_problem_deleted` (`is_deleted`);

ALTER TABLE `post`
    ADD COLUMN `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 AFTER `update_time`,
    ADD INDEX `idx_post_deleted` (`is_deleted`);

ALTER TABLE `courseware`
    ADD COLUMN `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 AFTER `update_time`;

ALTER TABLE `class`
    ADD COLUMN `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 AFTER `update_time`,
    ADD INDEX `idx_class_deleted` (`is_deleted`);

-- ============================================================
-- 5. Fix JSON column types (VARCHAR → JSON)
-- ============================================================

ALTER TABLE `problem`
    MODIFY COLUMN `tags` JSON DEFAULT NULL COMMENT '标签(JSON数组)';

ALTER TABLE `post`
    MODIFY COLUMN `images` JSON DEFAULT NULL COMMENT '图片URL数组';
