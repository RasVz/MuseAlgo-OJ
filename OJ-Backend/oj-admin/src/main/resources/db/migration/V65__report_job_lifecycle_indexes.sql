-- Online index creation for the report worker claim paths (split out of V64).
--
-- Two separate statements keep each metadata-lock window short. INPLACE + LOCK=NONE allows the
-- application to keep reading and writing report_job while the index is built; if the storage
-- engine cannot honour it the statement fails instead of silently falling back to a blocking copy
-- (MySQL only relaxes a requested lock level when it is strictly weaker, which is not the case here).

ALTER TABLE report_job
    ADD KEY ix_report_job_due (status, next_attempt_at, id),
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE report_job
    ADD KEY ix_report_job_expired_lease (status, lease_until, id),
    ALGORITHM=INPLACE, LOCK=NONE;
