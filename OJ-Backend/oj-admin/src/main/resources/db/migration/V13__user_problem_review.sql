CREATE TABLE IF NOT EXISTS `user_problem_review` (
  `user_id` BIGINT NOT NULL,
  `problem_id` BIGINT NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'REVIEW' COMMENT 'REVIEW/MASTERED',
  `reason` VARCHAR(64) NOT NULL DEFAULT 'REPEATED_WA',
  `first_flagged_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_reviewed_at` DATETIME NULL,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`, `problem_id`),
  KEY `idx_user_problem_review_status` (`user_id`, `status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户错题复习计划';
