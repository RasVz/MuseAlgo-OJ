-- Table: courseware
-- Database: oj_db

CREATE TABLE IF NOT EXISTS `courseware` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `teacher_id` bigint NOT NULL COMMENT '创建老师ID',
  `title` varchar(128) NOT NULL COMMENT '课件标题',
  `url` varchar(512) DEFAULT NULL COMMENT 'Slidev构建产物的访问地址 (供iframe加载)',
  `problem_list_id` bigint DEFAULT NULL COMMENT '关联的练习题单ID (供右侧栏显示)',
  `content` longtext COMMENT 'Slidev Markdown源码 (备份源码)',
  `description` varchar(255) DEFAULT NULL COMMENT '简介',
  `privacy` tinyint DEFAULT '0' COMMENT '0-私有, 1-公开',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_teacher` (`teacher_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='课件资源表'
;
