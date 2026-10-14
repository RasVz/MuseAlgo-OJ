-- Table: problem_contest
-- Database: oj_db

CREATE TABLE IF NOT EXISTS `problem_contest` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `contest_id` bigint NOT NULL COMMENT '竞赛ID',
  `problem_id` bigint NOT NULL COMMENT '题目ID',
  `problem_order` int DEFAULT '0' COMMENT '题目在竞赛中的顺序（如第1题、第2题）',
  `score` int DEFAULT '100' COMMENT '该题目在本场竞赛中的分值',
  `visible` tinyint(1) DEFAULT '1' COMMENT '是否可见：0-隐藏（赛前保密），1-可见',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint(1) DEFAULT '0' COMMENT '逻辑删除：0-未删除，1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_contest_problem` (`contest_id`,`problem_id`),
  KEY `idx_contest_id` (`contest_id`),
  KEY `idx_problem_id` (`problem_id`),
  KEY `idx_visible` (`visible`),
  KEY `idx_is_deleted` (`is_deleted`),
  KEY `idx_contest_visible` (`contest_id`,`visible`,`is_deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='竞赛-题目关联表'
;
