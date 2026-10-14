-- Table: user_badge
-- Database: oj_db

CREATE TABLE IF NOT EXISTS `user_badge` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `badge_id` bigint NOT NULL,
  `obtain_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '获得时间',
  `is_wearing` tinyint(1) DEFAULT '0' COMMENT '是否佩戴展示',
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户持有徽章表'
;
