-- Outbox 归档表（方案 2026-08-14 第 3 节）
-- 保留审计痕迹：PUBLISHED / FAILED / 卡死 PENDING 到期后由 OutboxArchiveTask 搬入本表，
-- 避免 outbox_event 无限膨胀导致 (status, next_attempt_at) 索引失效。

CREATE TABLE IF NOT EXISTS outbox_event_archive (
    id CHAR(36) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id BIGINT UNSIGNED NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(16) NOT NULL,
    retry_count INT UNSIGNED NOT NULL DEFAULT 0,
    published_at DATETIME NULL,
    last_error TEXT NULL,
    created_at DATETIME NOT NULL,
    archived_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_outbox_archive_archived (archived_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
