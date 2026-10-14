-- Table: class
-- Database: oj_db

CREATE TABLE IF NOT EXISTS `class` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) NOT NULL COMMENT '班级名称',
  `teacher_id` bigint NOT NULL COMMENT '创建教师ID',
  `student_count` int NOT NULL DEFAULT '0' COMMENT '学生人数',
  `invite_code` varchar(10) DEFAULT NULL COMMENT '邀请码',
  `description` varchar(255) DEFAULT NULL COMMENT '简介',
  `school` varchar(64) DEFAULT NULL COMMENT '所属机构',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_invite_code` (`invite_code`),
  KEY `idx_teacher` (`teacher_id`),
  KEY `idx_class_deleted` (`is_deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='班级表'
;
