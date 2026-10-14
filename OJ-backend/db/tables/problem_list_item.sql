-- Table: problem_list_item
-- Database: oj_db

CREATE TABLE IF NOT EXISTS `problem_list_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `list_id` bigint NOT NULL COMMENT '题单ID',
  `problem_id` bigint NOT NULL COMMENT '题目ID',
  `sort` int DEFAULT '0' COMMENT '排序(越小越前)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_list_problem` (`list_id`,`problem_id`) COMMENT '防止同一个单子里重复添加同一道题'
) ENGINE=InnoDB AUTO_INCREMENT=54 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='题单题目关联'
;
