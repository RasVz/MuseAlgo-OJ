-- Table: user_rating
-- Database: oj_db

CREATE TABLE IF NOT EXISTS `user_rating` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `contest_id` bigint NOT NULL COMMENT '比赛ID',
  `rating` int NOT NULL COMMENT '变动后的Rating值(用于画图)',
  `rating_change` int NOT NULL COMMENT '本次变动幅度(如 +50, -20)',
  `rank` int DEFAULT NULL COMMENT '比赛排名',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`) USING BTREE COMMENT '方便查询某人的历史',
  KEY `idx_contest` (`contest_id`)
) ENGINE=InnoDB AUTO_INCREMENT=62 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户Rating变化记录表'
;
