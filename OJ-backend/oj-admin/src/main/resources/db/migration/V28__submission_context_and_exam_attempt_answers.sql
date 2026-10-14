ALTER TABLE submission
    ADD COLUMN vp_attempt_id BIGINT NULL COMMENT '虚拟赛会话ID；正式比赛提交为空' AFTER contest_id,
    ADD INDEX idx_vp_attempt (vp_attempt_id);

ALTER TABLE exam_answer
    ADD COLUMN attempt_id BIGINT NULL COMMENT '作答会话ID；历史答案为空' AFTER paper_id,
    DROP INDEX uk_answer,
    ADD UNIQUE INDEX uk_attempt_answer (attempt_id, question_id);

ALTER TABLE vp_attempt
    ADD COLUMN solved_count INT NOT NULL DEFAULT 0 COMMENT 'ACM 模式解题数' AFTER score,
    ADD COLUMN penalty_time INT NOT NULL DEFAULT 0 COMMENT 'ACM 模式罚时（分钟）' AFTER solved_count;
