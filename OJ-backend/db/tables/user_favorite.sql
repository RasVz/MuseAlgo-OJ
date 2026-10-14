-- Table: user_favorite
-- Database: oj_db

CREATE TABLE IF NOT EXISTS `user_favorite` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '收藏者ID',
  `target_id` bigint NOT NULL COMMENT '被收藏内容ID',
  `target_type` tinyint NOT NULL COMMENT '目标类型: 1-题目, 2-题单, 3-比赛, 4-动态',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '收藏状态: 1-当前已收藏, 0-当前已取消',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次收藏时间（不变）',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后状态变更时间',
  `cancel_time` datetime DEFAULT NULL COMMENT '最近一次取消时间（status=0时有值，恢复收藏时置NULL）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_target` (`user_id`,`target_type`,`target_id`),
  KEY `idx_user_status` (`user_id`,`status`,`target_type`,`update_time`),
  KEY `idx_target_status` (`target_id`,`target_type`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通用收藏表（单记录状态机设计：取消即更新，不保留历史）'
;
