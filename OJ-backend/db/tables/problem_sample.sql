-- Table: problem_sample
-- Database: oj_db

CREATE TABLE IF NOT EXISTS `problem_sample` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `problem_id` bigint NOT NULL COMMENT '题目ID',
  `sample_order` int unsigned NOT NULL DEFAULT '1' COMMENT '样例顺序，从 1 开始',
  `input` text COMMENT '样例输入',
  `output` text COMMENT '样例输出',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_problem_sample_problem_order` (`problem_id`,`sample_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='题目公开样例表';
