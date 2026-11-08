-- 持久完成标记涵盖空榜；已有历史成绩仅标记，绝不重新计算旧 Rating。
ALTER TABLE contest ADD COLUMN rank_finalized_at DATETIME NULL;
CREATE INDEX idx_contest_settlement ON contest (is_deleted, rank_finalized_at, end_time);
CREATE TABLE contest_settlement_guard (id TINYINT NOT NULL PRIMARY KEY);
INSERT INTO contest_settlement_guard VALUES (1);
UPDATE contest c SET rank_finalized_at = COALESCE(c.end_time, c.update_time, NOW())
WHERE EXISTS (SELECT 1 FROM contest_rank r WHERE r.contest_id=c.id AND r.is_deleted=0)
   OR EXISTS (SELECT 1 FROM user_rating r WHERE r.contest_id=c.id);
