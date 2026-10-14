-- V10: 用户-标签 ELO 评分表（六维雷达图数据源）
-- 学生每 AC 一道题，根据题目的所有 tag 计算 ELO 增益并 upsert

CREATE TABLE IF NOT EXISTS `user_tag_rating` (
  `user_id`       BIGINT UNSIGNED NOT NULL,
  `tag_id`        BIGINT UNSIGNED NOT NULL,
  `rating`        INT             NOT NULL DEFAULT 800   COMMENT '当前 ELO 评分',
  `problem_count` INT             NOT NULL DEFAULT 0     COMMENT '该标签下 AC 的题目数',
  `update_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`, `tag_id`),
  KEY `idx_user_tag_rating_user` (`user_id`),
  KEY `idx_user_tag_rating_tag` (`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户标签ELO评分表';
