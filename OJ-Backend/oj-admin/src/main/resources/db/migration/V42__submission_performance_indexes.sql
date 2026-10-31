-- ============================================================
-- V42: submission 表性能索引（高并发方案 B2）
-- 说明:
--   1. idx_contest_vp_create  覆盖比赛提交页 (contest_id + vp_attempt_id IS NULL + create_time 排序)
--     与赛后排名重建 (ORDER BY create_time ASC, id ASC)
--   2. idx_user_create        覆盖用户提交统计 (user_id + create_time 范围)
-- 注意: 大表加索引会锁表，建议低峰期执行（或使用 pt-osc / gh-ost 在线变更）
-- ============================================================

ALTER TABLE submission
    ADD INDEX idx_contest_vp_create (contest_id, vp_attempt_id, create_time),
    ADD INDEX idx_user_create (user_id, create_time);
