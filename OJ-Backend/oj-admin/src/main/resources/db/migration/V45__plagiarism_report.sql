-- ============================================================
-- V45: 代码查重报告表（M1 班级内比对）
-- 归属模块：oj-analytics（数据经 SubmissionCodeQuery 契约读取）
-- ============================================================
CREATE TABLE IF NOT EXISTS plagiarism_report (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    class_id BIGINT NOT NULL,
    problem_id BIGINT NOT NULL,
    submission_a_id BIGINT NOT NULL,
    submission_b_id BIGINT NOT NULL,
    user_a_id BIGINT NOT NULL,
    user_b_id BIGINT NOT NULL,
    similarity DOUBLE NOT NULL,
    method VARCHAR(16) NOT NULL DEFAULT 'AST',          -- AST / TOKEN
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',      -- PENDING / REVIEWED / IGNORED
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pair (class_id, problem_id, submission_a_id, submission_b_id),
    KEY idx_plagiarism_class (class_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
