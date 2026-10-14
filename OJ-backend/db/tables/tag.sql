-- Table: tag
-- Database: oj_db
-- Source: Flyway V5 migration (V5__knowledge_graph.sql)
-- Note: 实体类 Tag.java 缺少 update_time 字段，以 Flyway DDL 为准

CREATE TABLE IF NOT EXISTS `tag` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`        VARCHAR(64)  NOT NULL                  COMMENT '程序标识，如 dp_knapsack；唯一',
  `name`        VARCHAR(64)  NOT NULL                  COMMENT '展示名，如 背包DP',
  `parent_id`   BIGINT UNSIGNED NULL                   COMMENT '父标签；NULL=知识域，指向知识域=知识点',
  `dimension`   VARCHAR(32)  NOT NULL                  COMMENT '知识域: ALGORITHM/DATA_STRUCTURE/MATH/GRAPH/STRING',
  `sort_order`  INT          NOT NULL DEFAULT 0        COMMENT '排序序号',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tag_code` (`code`),
  KEY `idx_tag_parent` (`parent_id`),
  KEY `idx_tag_dimension` (`dimension`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='标签字典（三层知识图谱）';
