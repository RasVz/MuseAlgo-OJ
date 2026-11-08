ALTER TABLE problem
    ADD COLUMN test_case_count INT NOT NULL DEFAULT 0 COMMENT '测试用例数量' AFTER total_count;

UPDATE problem p
LEFT JOIN (
    SELECT problem_id, COUNT(*) AS case_count
    FROM problem_case
    GROUP BY problem_id
) pc ON pc.problem_id = p.id
SET p.test_case_count = COALESCE(pc.case_count, 0);
