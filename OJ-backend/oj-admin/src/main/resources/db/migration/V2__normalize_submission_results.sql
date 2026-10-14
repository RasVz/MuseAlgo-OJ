-- Keep submission as immutable request metadata and move judged output to 1:1 result tables.

CREATE TABLE IF NOT EXISTS submission_result (
    submission_id BIGINT NOT NULL,            -- 与 submission.id 类型一致
    status TINYINT UNSIGNED NOT NULL,
    score INT UNSIGNED NOT NULL DEFAULT 0,
    run_time INT UNSIGNED NOT NULL DEFAULT 0,
    run_memory INT UNSIGNED NOT NULL DEFAULT 0,
    error_message TEXT NULL,
    judged_at DATETIME NOT NULL,
    PRIMARY KEY (submission_id),
    CONSTRAINT fk_submission_result_submission
        FOREIGN KEY (submission_id) REFERENCES submission (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS submission_case_result (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    submission_id BIGINT NOT NULL,            -- 与 submission.id 类型一致
    problem_case_id BIGINT NULL,
    case_order INT UNSIGNED NOT NULL,
    status TINYINT UNSIGNED NOT NULL,
    run_time INT UNSIGNED NOT NULL DEFAULT 0,
    run_memory INT UNSIGNED NOT NULL DEFAULT 0,
    error_message TEXT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_submission_case_result_submission_order (submission_id, case_order),
    CONSTRAINT fk_submission_case_result_submission
        FOREIGN KEY (submission_id) REFERENCES submission (id),
    CONSTRAINT fk_submission_case_result_case
        FOREIGN KEY (problem_case_id) REFERENCES problem_case (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Existing rows remain readable from submission during the compatibility phase.
INSERT IGNORE INTO submission_result
    (submission_id, status, score, run_time, run_memory, error_message, judged_at)
SELECT id, status, CAST(COALESCE(NULLIF(score, ''), '0') AS UNSIGNED),
       COALESCE(run_time, 0), COALESCE(run_memory, 0), error_message,
       COALESCE(create_time, CURRENT_TIMESTAMP)
FROM submission
WHERE status IS NOT NULL AND status <> 0;
