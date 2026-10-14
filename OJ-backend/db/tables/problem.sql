-- Table: problem
-- Database: oj_db

CREATE TABLE IF NOT EXISTS `problem` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '题目ID',
  `title` varchar(128) NOT NULL COMMENT '题目标题',
  `contest_id` int DEFAULT NULL COMMENT '关联的比赛ID',
  `content` longtext COMMENT '题目内容(Markdown)',
  `input_desc` text COMMENT '输入描述',
  `output_desc` text COMMENT '输出描述',
  `hint` text COMMENT '题目提示(Markdown)',
  `time_limit` int DEFAULT '1000' COMMENT '时间限制(ms)',
  `memory_limit` int DEFAULT '512' COMMENT '内存限制(MB)',
  `difficulty` int NOT NULL DEFAULT '800' COMMENT '难度分数 800 - 3000',
  `tags` varchar(255) DEFAULT NULL COMMENT '标签JSON: ["DP","Array"]',
  `pass_count` int DEFAULT '0' COMMENT '通过次数',
  `total_count` int DEFAULT '0' COMMENT '提交总数',
  `test_case_count` int NOT NULL DEFAULT '0' COMMENT '测试用例数量',
  `author_id` bigint DEFAULT NULL COMMENT '出题人ID',
  `source` varchar(200) DEFAULT 'System' COMMENT '题目来源（如 LeetCode, NOIP）',
  `is_public` tinyint(1) DEFAULT '1' COMMENT '0-私有, 1-公开',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '题目创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_author` (`author_id`),
  KEY `idx_public_difficulty` (`is_public`,`difficulty`),
  KEY `idx_problem_deleted` (`is_deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=1031 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='题目表'
;
