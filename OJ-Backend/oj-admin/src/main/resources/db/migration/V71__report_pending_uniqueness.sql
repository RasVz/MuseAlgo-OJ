-- Only simultaneous pending reports are duplicates; retain multiple completed cycles.
ALTER TABLE content_report
    DROP INDEX uk_reporter_post_pending,
    ADD COLUMN pending_reporter_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN status = 'PENDING' THEN reporter_id ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_reporter_post_pending (pending_reporter_id, post_id);
