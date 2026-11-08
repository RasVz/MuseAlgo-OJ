-- Bind legacy report requests to the new requester column, in bounded batches.
--
-- Split out of V64 so the row-by-row work is interruptible and rate-limited instead of extending
-- the DDL's lock window. The WHERE clause makes the backfill idempotent: a failed or interrupted
-- run can simply be retried, and rows already bound are never rewritten.
--
-- lock_wait_timeout makes each batch yield to application traffic instead of queueing behind it.
-- 1000 rows per statement keeps the transaction short; the 50 ms pause gives other sessions room.

SET SESSION lock_wait_timeout = 5;

DROP PROCEDURE IF EXISTS backfill_report_job_active_requester;
DELIMITER $$
CREATE PROCEDURE backfill_report_job_active_requester()
BEGIN
    DECLARE affected INT DEFAULT 1;
    WHILE affected > 0 DO
        UPDATE report_job
           SET active_requester_id = requester_id
         WHERE requester_id IS NOT NULL
           AND active_requester_id IS NULL
         LIMIT 1000;
        SET affected = ROW_COUNT();
        IF affected > 0 THEN
            DO SLEEP(0.05);
        END IF;
    END WHILE;
END$$
DELIMITER ;

CALL backfill_report_job_active_requester();
DROP PROCEDURE IF EXISTS backfill_report_job_active_requester;
