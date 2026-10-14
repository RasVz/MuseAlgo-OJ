-- Table: badge
-- Database: oj_db

CREATE TABLE IF NOT EXISTS `badge` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(32) NOT NULL COMMENT '徽章名称 (如: 刷题狂魔)',
  `icon` varchar(255) NOT NULL COMMENT '图标/图片URL',
  `description` varchar(255) DEFAULT NULL COMMENT '获取条件描述',
  `condition_type` varchar(32) DEFAULT NULL COMMENT '触发条件类型 (如: ac_count)',
  `condition_value` int DEFAULT '0' COMMENT '触发阈值 (如: 100)',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统徽章表'
;
