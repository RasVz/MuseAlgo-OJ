-- Table: problem_tag
-- Database: oj_db
-- Source: Flyway V5 migration (V5__knowledge_graph.sql)
-- Note: 实体类 ProblemTag.java 与 DDL 完全一致

CREATE TABLE IF NOT EXISTS `problem_tag` (
  `problem_id`  BIGINT UNSIGNED NOT NULL,
  `tag_id`      BIGINT UNSIGNED NOT NULL,
  `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`problem_id`, `tag_id`),
  KEY `idx_problem_tag_tag` (`tag_id`) COMMENT '画像统计核心索引：按 tag 反查题目'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='题目-标签关联';
