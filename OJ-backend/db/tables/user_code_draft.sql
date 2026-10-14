-- Table: user_code_draft
-- Database: oj_db

CREATE TABLE IF NOT EXISTS `user_code_draft` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `problem_id` bigint NOT NULL COMMENT '题目ID',
  `language` varchar(20) NOT NULL COMMENT '编程语言 (如 cpp, java, python)',
  `code` text COMMENT '代码内容',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_problem_lang` (`user_id`,`problem_id`,`language`) COMMENT '确保同人同题同语言只有一条草稿',
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户代码草稿表'
;
