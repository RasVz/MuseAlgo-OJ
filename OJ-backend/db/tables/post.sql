-- Table: post
-- Database: oj_db

CREATE TABLE IF NOT EXISTS `post` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(128) NOT NULL COMMENT '标题',
  `content` longtext NOT NULL COMMENT 'Markdown内容',
  `summary` varchar(256) DEFAULT NULL COMMENT '纯文本摘要(用于列表展示)',
  `author_id` bigint NOT NULL COMMENT '作者ID',
  `problem_id` bigint DEFAULT NULL COMMENT '关联题目ID',
  `contest_id` bigint DEFAULT NULL COMMENT '关联比赛ID',
  `class_id` bigint DEFAULT NULL COMMENT '关联班级ID',
  `type` tinyint NOT NULL DEFAULT '0' COMMENT '文章类型: 0-动态, 1-题解, 2-博客',
  `category` varchar(32) DEFAULT 'algorithm' COMMENT '分类: announcement/solution/algorithm/share',
  `tags` varchar(255) DEFAULT NULL COMMENT '如 ["DP", "Java"]',
  `is_top` tinyint(1) NOT NULL DEFAULT '0' COMMENT '置顶',
  `is_essence` tinyint(1) NOT NULL DEFAULT '0' COMMENT '加精',
  `views` int DEFAULT '0' COMMENT '浏览数',
  `likes` int DEFAULT '0' COMMENT '点赞数',
  `comment_count` int DEFAULT '0' COMMENT '评论数',
  `privacy` tinyint DEFAULT '0' COMMENT '0-公开, 1-私有',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0-正常, 1-已删除',
  `images` varchar(2000) DEFAULT NULL COMMENT '动态图片列表，逗号分隔，最多9张',
  `dislikes` int NOT NULL DEFAULT '0' COMMENT '踩数',
  `favorites` int NOT NULL DEFAULT '0' COMMENT '收藏数',
  `score` double NOT NULL DEFAULT '0' COMMENT '热度缓存分(仅用于排序参考)',
  `hot_calculated_at` timestamp NULL DEFAULT NULL COMMENT '上次热度计算时间',
  PRIMARY KEY (`id`),
  KEY `idx_context` (`contest_id`,`problem_id`),
  KEY `idx_author` (`author_id`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_type_create` (`type`,`create_time`),
  KEY `idx_post_deleted` (`is_deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=14 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='帖子表'
;
