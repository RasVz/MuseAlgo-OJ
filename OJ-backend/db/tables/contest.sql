-- Table: contest
-- Database: oj_db

CREATE TABLE IF NOT EXISTS `contest` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `title` varchar(255) NOT NULL COMMENT '赛事名称',
  `description` text COMMENT '赛事描述/规则',
  `start_time` datetime NOT NULL COMMENT '开始时间',
  `end_time` datetime NOT NULL COMMENT '结束时间',
  `duration` int NOT NULL COMMENT '时长(分钟)',
  `type` varchar(50) DEFAULT 'Public' COMMENT '类型: Official, Public, Private',
  `author_id` bigint DEFAULT NULL COMMENT '举办人ID',
  `author_name` varchar(100) DEFAULT NULL COMMENT '举办人名称',
  `participant_count` int DEFAULT '0' COMMENT '参赛人数',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint(1) DEFAULT '0' COMMENT '逻辑删除',
  `class_id` bigint DEFAULT NULL COMMENT '所属班级ID',
  `visible` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0不可见，1可见',
  PRIMARY KEY (`id`),
  KEY `idx_author` (`author_id`),
  KEY `idx_time_range` (`start_time`,`end_time`)
) ENGINE=InnoDB AUTO_INCREMENT=651 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='竞赛表'
;
