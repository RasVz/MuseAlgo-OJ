-- Table: submission
-- Database: oj_db

CREATE TABLE IF NOT EXISTS `submission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `problem_id` bigint NOT NULL COMMENT '题目ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `code` longtext NOT NULL COMMENT '代码',
  `language` varchar(32) NOT NULL DEFAULT 'java' COMMENT '语言',
  `score` int DEFAULT '0' COMMENT '分数',
  `status` tinyint DEFAULT '0' COMMENT '0-待判, 1-AC, 2-WA, 3-CE, 4-RE, 5-TLE',
  `run_time` int DEFAULT '0' COMMENT '耗时(ms)',
  `run_memory` int DEFAULT '0' COMMENT '内存(KB)',
  `error_message` text COMMENT '错误信息(编译错误/堆栈)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `contest_id` bigint DEFAULT NULL COMMENT '比赛ID（非赛时为空）',
  `vp_attempt_id` bigint DEFAULT NULL COMMENT '虚拟赛会话ID；正式比赛提交为空',
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_problem` (`problem_id`),
  KEY `idx_contest` (`contest_id`),
  KEY `idx_vp_attempt` (`vp_attempt_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10081 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评测记录表'
;
