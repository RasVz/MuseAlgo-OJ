-- Table: comment
-- Database: oj_db

CREATE TABLE IF NOT EXISTS `comment` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '评论者ID (谁发的)',
  `entity_id` bigint NOT NULL COMMENT '被评论对象的ID (例如动态ID、题目ID)',
  `entity_type` tinyint NOT NULL DEFAULT '1' COMMENT '评论类型 (1:动态 2:题目 3:题解) - 方便以后复用',
  `content` text NOT NULL COMMENT '评论内容',
  `parent_id` bigint DEFAULT '0' COMMENT '父评论ID (0表示这是一条根评论)',
  `reply_user_id` bigint DEFAULT '0' COMMENT '被回复者ID (如果是在回复某人)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` tinyint(1) DEFAULT '0' COMMENT '逻辑删除',
  `like_count` int DEFAULT '0' COMMENT '点赞数',
  `reply_count` int DEFAULT '0' COMMENT '回复数',
  PRIMARY KEY (`id`),
  KEY `idx_entity` (`entity_id`,`entity_type`) USING BTREE COMMENT '查某个动态下的所有评论',
  KEY `idx_user` (`user_id`) USING BTREE COMMENT '查某人发过的评论'
) ENGINE=InnoDB AUTO_INCREMENT=22 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评论表'
;
