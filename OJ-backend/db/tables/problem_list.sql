-- Table: problem_list
-- Database: oj_db

CREATE TABLE IF NOT EXISTS `problem_list` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `title` varchar(128) NOT NULL COMMENT '题单标题',
  `creator_id` bigint NOT NULL COMMENT '创建人ID (关联 sys_user)',
  `description` text COMMENT '题单简介 (支持 Markdown 格式)',
  `type` tinyint NOT NULL DEFAULT '1' COMMENT '类型: 1-个人题单, 2-官方题单, 3-班级题单, 4-班级作业',
  `class_id` bigint DEFAULT NULL COMMENT '所属班级ID (仅 type=2 或者 type=3 作业时有效)',
  `privacy` tinyint NOT NULL DEFAULT '0' COMMENT '权限: 0-私有, 1-公开',
  `start_time` datetime DEFAULT NULL COMMENT '作业开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '作业截止时间',
  `problem_count` int NOT NULL DEFAULT '0' COMMENT '题目数量 (缓存)',
  `favorite_count` int NOT NULL DEFAULT '0' COMMENT '收藏/引用次数',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_creator` (`creator_id`) USING BTREE COMMENT '创建人索引',
  KEY `idx_class` (`class_id`) USING BTREE COMMENT '班级索引',
  KEY `idx_type` (`type`)
) ENGINE=InnoDB AUTO_INCREMENT=219 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='题单与作业主表'
;
