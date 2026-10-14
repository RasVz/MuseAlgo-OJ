-- Table: student_profile_snapshot
-- Database: oj_db
-- Source: Flyway V5 migration (V5__knowledge_graph.sql)
-- Note: 实体类 StudentProfileSnapshot.java 与 DDL 完全一致

CREATE TABLE IF NOT EXISTS `student_profile_snapshot` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `student_id`     BIGINT UNSIGNED NOT NULL,
  `class_id`       BIGINT UNSIGNED NULL,
  `period_start`   DATE            NOT NULL              COMMENT '周期开始（周一）',
  `period_end`     DATE            NOT NULL              COMMENT '周期结束（周日）',
  `snapshot_json`  JSON            NOT NULL              COMMENT '完整画像 JSON（mastery/percentile/趋势）',
  `report_url`     VARCHAR(512)    NULL                  COMMENT '家长报告 PDF 的 COS 地址',
  `create_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_profile_student_period` (`student_id`, `period_start`),
  KEY `idx_profile_class_period` (`class_id`, `period_start`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学生画像周快照';
