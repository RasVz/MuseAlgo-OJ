-- Table: user
-- Database: oj_db

CREATE TABLE IF NOT EXISTS `user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username` varchar(64) NOT NULL COMMENT '账号(唯一)',
  `password` varchar(128) NOT NULL COMMENT '加密密码',
  `nickname` varchar(64) NOT NULL COMMENT '昵称',
  `role` tinyint NOT NULL DEFAULT '1' COMMENT '1-学生, 2-老师, 3-管理员',
  `email` varchar(64) DEFAULT NULL COMMENT '邮箱',
  `avatar` text COMMENT '头像URL',
  `real_name` varchar(32) DEFAULT NULL COMMENT '真实姓名(实名认证用)',
  `school` varchar(64) DEFAULT NULL COMMENT '学校/机构',
  `major` varchar(64) DEFAULT NULL COMMENT '专业/年级',
  `motto` varchar(255) DEFAULT NULL COMMENT '个性签名(Signature)',
  `blog` varchar(255) DEFAULT NULL COMMENT '个人博客链接',
  `github` varchar(255) DEFAULT NULL COMMENT 'Github链接',
  `preferences` json DEFAULT NULL COMMENT '用户偏好设置(JSON)',
  `level` int DEFAULT '1' COMMENT '用户等级',
  `experience` int DEFAULT '0' COMMENT '经验值(通过题目/比赛获得)',
  `follower_count` int DEFAULT '0' COMMENT '粉丝数',
  `following_count` int DEFAULT '0' COMMENT '关注数',
  `like_count` int DEFAULT '0' COMMENT '获赞数',
  `submit_count` int DEFAULT '0' COMMENT '提交总数',
  `accepted_count` int DEFAULT '0' COMMENT '通过总数',
  `rating` int DEFAULT '1200' COMMENT '竞赛积分',
  `analysis` json DEFAULT NULL COMMENT '能力值分析缓存',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` tinyint(1) DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_email` (`email`)
) ENGINE=InnoDB AUTO_INCREMENT=29 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户核心表'
;
