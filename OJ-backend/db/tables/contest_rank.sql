-- Table: contest_rank
-- Database: oj_db

CREATE TABLE IF NOT EXISTS `contest_rank` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `contest_id` bigint NOT NULL COMMENT '比赛ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `rank_num` int NOT NULL COMMENT '最终名次',
  `solved_count` int DEFAULT '0' COMMENT '解题数量',
  `penalty_time` int DEFAULT '0' COMMENT '罚时(分钟)',
  `total_score` int DEFAULT '0' COMMENT '总分(IOI赛制)',
  `rating_before` int DEFAULT NULL COMMENT '赛前Rating',
  `rating_after` int DEFAULT NULL COMMENT '赛后Rating',
  `award` varchar(50) DEFAULT NULL COMMENT '奖项(金牌/银牌/铜牌/优秀奖)',
  `is_visible` tinyint(1) DEFAULT '1' COMMENT '是否可见(0-隐藏,1-可见)',
  `is_deleted` tinyint(1) DEFAULT '0' COMMENT '是否逻辑删除(0-未删除,1-已删除)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_contest_user` (`contest_id`,`user_id`) COMMENT '比赛用户唯一',
  KEY `idx_contest_rank` (`contest_id`,`rank_num`) COMMENT '比赛排名查询',
  KEY `idx_user_contest` (`user_id`,`contest_id`) COMMENT '用户比赛查询',
  KEY `idx_create_time` (`create_time`) COMMENT '时间范围查询'
) ENGINE=InnoDB AUTO_INCREMENT=41 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='比赛排行榜'
;
