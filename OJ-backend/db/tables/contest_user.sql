-- Table: contest_user
-- Database: oj_db

CREATE TABLE IF NOT EXISTS `contest_user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `contest_id` bigint NOT NULL COMMENT '比赛ID',
  `user_id` bigint NOT NULL COMMENT '报名用户ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '报名时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_contest_user` (`contest_id`,`user_id`) COMMENT '确保同人同比赛只能报名一次',
  KEY `idx_user_id` (`user_id`) COMMENT '方便查询某个用户报名了哪些比赛'
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='比赛报名记录表'
;
