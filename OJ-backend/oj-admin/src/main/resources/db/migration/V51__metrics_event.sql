-- ============================================================
-- V51: 前端体验埋点聚合表（2026-08-15）
-- 说明:
--   1. 聚合式设计（非明细）：按 (event_day, event_type, event_key)
--      当日 upsert 计数，避免明细表膨胀；匿名无用户关联。
--   2. 事件类型 4 种：route / route_slow / api_fail / js_error。
--   3. 幂等（IF NOT EXISTS）：本迁移曾以 V49 编号合入 master，
--      与并行提交撞号后改号为 V51，建表可安全重复执行。
-- ============================================================

CREATE TABLE IF NOT EXISTS `metrics_event` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `event_day`  DATE         NOT NULL COMMENT '统计日（自然日）',
    `event_type` VARCHAR(20)  NOT NULL COMMENT '事件类型: route=路由切换 / route_slow=慢切换(>500ms) / api_fail=接口失败 / js_error=页面错误',
    `event_key`  VARCHAR(120) NOT NULL COMMENT '事件对象: 路由名 / 接口路径 / 错误消息(前200字)',
    `count`      INT          NOT NULL DEFAULT 1 COMMENT '当日累计次数',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次记录时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_day_type_key` (`event_day`, `event_type`, `event_key`),
    KEY `idx_day_type` (`event_day`, `event_type`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT ='前端体验埋点聚合事件（匿名，无用户关联）';
