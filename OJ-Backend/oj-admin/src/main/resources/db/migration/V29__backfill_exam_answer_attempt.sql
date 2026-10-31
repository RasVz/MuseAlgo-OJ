-- V28 开始按 attempt 统计答案；将升级前的历史答案绑定到最近一次已完成作答。
UPDATE exam_answer ea
JOIN (
    SELECT paper_id, user_id, MAX(id) AS attempt_id
    FROM exam_attempt
    WHERE status <> 'RUNNING'
    GROUP BY paper_id, user_id
) latest
    ON latest.paper_id = ea.paper_id
    AND latest.user_id = ea.user_id
SET ea.attempt_id = latest.attempt_id
WHERE ea.attempt_id IS NULL;
