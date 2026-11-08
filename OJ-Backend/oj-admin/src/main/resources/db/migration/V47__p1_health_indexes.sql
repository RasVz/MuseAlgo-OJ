-- ============================================================
-- V47: P1 数据健康索引补充（2026-08-14 优先级清单第一批）
-- 说明:
--   1. comment_like 缺 comment_id 索引：按评论删除点赞 / 统计点赞数
--      当前只有 (user_id, comment_id) 唯一键，comment_id 前缀无法命中，
--      需按评论维度查询时全表扫描。
--   2. submission 画像聚合子查询缺复合索引：
--      selectProfileByTag/Dimension 的 firstTryPassed 子查询
--      WHERE user_id = ? AND problem_id = ? AND create_time < ?
--      现有 idx_user_create (user_id, create_time) 无法覆盖 problem_id 过滤。
-- 注意: submission 大表加索引会锁表，建议低峰期执行（或 pt-osc / gh-ost 在线变更）。
-- ============================================================

ALTER TABLE `comment_like`
    ADD INDEX `idx_comment_id` (`comment_id`);

ALTER TABLE `submission`
    ADD INDEX `idx_user_problem_create` (`user_id`, `problem_id`, `create_time`);
