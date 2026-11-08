-- Retention uses a bounded create_time range after status. Request online index creation;
-- fail instead of silently falling back to a blocking table copy if it is unsupported.
ALTER TABLE report_job
    ADD KEY ix_report_job_retention (status, create_time, id),
    ALGORITHM=INPLACE, LOCK=NONE;
