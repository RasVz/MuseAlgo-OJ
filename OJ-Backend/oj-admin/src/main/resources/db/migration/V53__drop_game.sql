-- ============================================================
-- V53: 睿思星球（游戏化模块）下线
-- 保留：种子题 4000~4008 为 problem 表普通题，继续可用
-- ============================================================

DROP TABLE IF EXISTS `submission_trace`;
DROP TABLE IF EXISTS `xp_log`;
DROP TABLE IF EXISTS `user_progress`;
DROP TABLE IF EXISTS `level`;

-- 可选收尾：种子题来源原为星球地名（Game·数组平原等），改为普通来源
UPDATE problem SET source = '官方题库' WHERE id BETWEEN 4000 AND 4008 AND source LIKE 'Game·%';
