-- Hint Ladder 解锁轨迹（V44，与 V43 outbox_archive 并列）
-- 权威历史：解锁即落库，供教师查看与掌握度算法消费；
-- Redis 仅作"当前级别"缓存（TTL 7 天），刷新丢失后可从本表恢复。
CREATE TABLE IF NOT EXISTS ai_hint_trace (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    problem_id BIGINT NOT NULL,
    level TINYINT NOT NULL,              -- 本次解锁到的级别 1-4
    source VARCHAR(16) NOT NULL DEFAULT 'USER',  -- USER / TEACHER / ADMIN
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_hint_trace_user_time (user_id, created_at),
    KEY idx_hint_trace_problem (problem_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
