-- Table: class_courseware
-- Database: oj_db

CREATE TABLE IF NOT EXISTS `class_courseware` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `class_id` bigint NOT NULL,
  `courseware_id` bigint NOT NULL COMMENT '课件ID',
  `sort` int DEFAULT '0' COMMENT '排序 (用于目录上下移动)',
  `is_visible` tinyint(1) DEFAULT '1' COMMENT '0-隐藏 1-显示 (默认显示)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_class` (`class_id`),
  KEY `idx_courseware` (`courseware_id`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='班级课件关联'
;
