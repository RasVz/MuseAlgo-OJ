-- 账号计数缓存列回填（T7）
-- user.submit_count   = 总提交数（含待判/失败提交）
-- user.accepted_count = 通过的不同题目数（status=1 去重）
--
-- 这两列此前只由 db/seed.sql 写入，在线提交/判题不会更新，导致个人主页、总榜与
-- 练习分析数据陈旧（用户本轮批准修复）。本迁移按 submission 表全量回算一次，
-- 后续由提交链路/判题完成链路增量维护。
--
-- 注意（T8 受控重判）：重判改变提交结果后，必须重新按 submission 全表重算这两列，
-- 只做增量会与真实流水漂移；重判方案需把该重算作为恢复步骤之一。
UPDATE `user` u
SET submit_count = (SELECT COUNT(*) FROM submission s WHERE s.user_id = u.id),
    accepted_count = (SELECT COUNT(DISTINCT s.problem_id) FROM submission s WHERE s.user_id = u.id AND s.status = 1);
