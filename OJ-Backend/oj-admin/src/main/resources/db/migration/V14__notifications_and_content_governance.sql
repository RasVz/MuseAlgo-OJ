CREATE TABLE IF NOT EXISTS `user_notification` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL COMMENT '接收人',
  `actor_id` BIGINT NULL COMMENT '触发人',
  `type` VARCHAR(32) NOT NULL COMMENT 'COMMENT/REPLY/LIKE/FAVORITE/FOLLOW_POST/CONTEST_REMINDER/HOMEWORK_REMINDER',
  `target_type` VARCHAR(24) NULL COMMENT 'post/contest/homework',
  `target_id` BIGINT NULL,
  `title` VARCHAR(160) NOT NULL,
  `content` VARCHAR(500) NULL,
  `is_read` TINYINT(1) NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `read_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_notification_user_read_time` (`user_id`, `is_read`, `create_time`),
  KEY `idx_notification_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='站内通知';

CREATE TABLE IF NOT EXISTS `content_report` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `reporter_id` BIGINT NOT NULL,
  `post_id` BIGINT NOT NULL,
  `reason` VARCHAR(64) NOT NULL,
  `detail` VARCHAR(500) NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RESOLVED/DISMISSED',
  `handler_id` BIGINT NULL,
  `handle_note` VARCHAR(500) NULL,
  `handled_at` DATETIME NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reporter_post_pending` (`reporter_id`, `post_id`, `status`),
  KEY `idx_content_report_status_time` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='社区内容举报';

CREATE TABLE IF NOT EXISTS `community_tag` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(64) NOT NULL,
  `group_name` VARCHAR(64) NOT NULL DEFAULT '自定义标签',
  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
  `created_by` BIGINT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_community_tag_name` (`name`),
  KEY `idx_community_tag_group` (`group_name`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='社区受控标签扩展';
