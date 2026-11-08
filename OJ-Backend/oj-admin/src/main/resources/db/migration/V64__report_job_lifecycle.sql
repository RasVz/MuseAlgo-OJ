-- Durable report job lifecycle and notice-owned transactional outbox.
-- V1-V63 remain frozen. This migration is the sole report-owned schema change.
--
-- Release-gate note (B4-1): the nine columns are appended (no AFTER clause) and the ALTER pins
-- ALGORITHM=INSTANT, so an existing report_job is not rebuilt and no long table copy or metadata
-- lock window is created. Index creation and the requester backfill are separate migrations
-- (V65/V66) so a slow backfill can never extend this DDL's lock window.

ALTER TABLE report_job
    ADD COLUMN active_requester_id BIGINT NULL COMMENT '当前有效报告请求主体；仅显式 retry 可重绑',
    ADD COLUMN generation_no BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '单调递增的执行代次',
    ADD COLUMN terminal_revision BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '单调递增的报告终态修订',
    ADD COLUMN snapshot_id BIGINT UNSIGNED NULL COMMENT '本任务实际写入的画像快照ID；历史未知保持NULL',
    ADD COLUMN lease_owner CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前执行租约所有者',
    ADD COLUMN lease_until DATETIME(3) NULL COMMENT '由数据库时间计算的执行租约截止时间',
    ADD COLUMN heartbeat_at DATETIME(3) NULL COMMENT '最近一次成功续租时间',
    ADD COLUMN next_attempt_at DATETIME(3) NULL COMMENT 'PENDING任务下一次可领取时间',
    ADD COLUMN last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '安全错误码，不含正文',
    ALGORITHM=INSTANT;

CREATE TABLE report_job_attempt (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    job_id BIGINT UNSIGNED NOT NULL,
    attempt_no SMALLINT UNSIGNED NOT NULL,
    generation_no BIGINT UNSIGNED NOT NULL,
    trigger_source VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id BIGINT NULL,
    worker_ref CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    stage VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at DATETIME(3) NULL,
    outcome VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    error_message VARCHAR(200) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_job_attempt (job_id, attempt_no),
    KEY ix_report_job_attempt_generation (job_id, generation_no, stage),
    CONSTRAINT fk_report_job_attempt_job
        FOREIGN KEY (job_id) REFERENCES report_job (id) ON DELETE CASCADE,
    CONSTRAINT ck_report_job_attempt_no CHECK (attempt_no BETWEEN 1 AND 3),
    CONSTRAINT ck_report_job_attempt_stage CHECK (stage IN ('GENERATING', 'FINALIZING')),
    CONSTRAINT ck_report_job_attempt_finished CHECK (
        (outcome IS NULL AND finished_at IS NULL) OR
        (outcome IS NOT NULL AND finished_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='报告生成尝试审计，不保存HTML或个人画像正文';

-- Owned by notice; this approved DDL is embedded here so terminal jobs and intents
-- share the same MySQL transaction. Notice owns all runtime reads and writes.
CREATE TABLE report_notification_intent (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '通知意图主键',
    event_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定事件去重键',
    event_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'REPORT_READY/REPORT_FAILED',
    job_id BIGINT UNSIGNED NOT NULL COMMENT 'report_job.id',
    class_id BIGINT NOT NULL COMMENT '授权上下文班级',
    student_id BIGINT NOT NULL COMMENT '授权上下文学生',
    recipient_id BIGINT NULL COMMENT '原始通知接收者；仅未分配失败意图可空',
    generation_no BIGINT UNSIGNED NOT NULL COMMENT '生成代次',
    terminal_revision BIGINT UNSIGNED NOT NULL COMMENT '终态修订号',
    source_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '不可变报告生成错误码；仅 REPORT_FAILED 非空',
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'PENDING/DISPATCHING/DELIVERED/SKIPPED_*/FAILED',
    attempts SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已提交的累计 dispatch claim 数',
    max_attempts SMALLINT UNSIGNED NOT NULL DEFAULT 5 COMMENT '本轮总预算；首轮 5，单次 redrive 后 10',
    redrive_round TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=首次预算，1=唯一一次管理员 redrive',
    dispatch_generation BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '每次 claim 单调递增的 fencing token',
    dispatch_owner CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'claim owner UUID',
    dispatch_lease_until DATETIME(3) NULL COMMENT '由数据库时间计算的 dispatch lease',
    next_attempt_at DATETIME(3) NULL COMMENT 'PENDING 到期时间；非 PENDING 可空',
    last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '不含正文的安全通知错误码',
    notification_id BIGINT NULL COMMENT '成功写入的 user_notification.id',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    delivered_at DATETIME(3) NULL,
    redrive_admin_id BIGINT NULL COMMENT '执行唯一一次 redrive 的管理员；不设用户外键',
    redriven_at DATETIME(3) NULL,
    redrive_reason VARCHAR(200) NULL COMMENT '管理员填写的 10-200 字理由；不得包含姓名或报告正文',
    PRIMARY KEY (id),
    UNIQUE KEY uk_rni_event_key (event_key),
    UNIQUE KEY uk_rni_notification_id (notification_id),
    KEY ix_rni_pending_due (status, next_attempt_at, id),
    KEY ix_rni_expired_lease (status, dispatch_lease_until, id),
    KEY ix_rni_job_event_revision_status (job_id, event_kind, terminal_revision, status),
    KEY ix_rni_recipient_job_revision (recipient_id, job_id, terminal_revision, event_kind),
    KEY ix_rni_admin_status_updated (status, updated_at, id),
    CONSTRAINT fk_rni_report_job
        FOREIGN KEY (job_id) REFERENCES report_job (id) ON DELETE RESTRICT,
    CONSTRAINT fk_rni_user_notification
        FOREIGN KEY (notification_id) REFERENCES user_notification (id) ON DELETE RESTRICT,
    CONSTRAINT ck_rni_event_kind CHECK (event_kind IN ('REPORT_READY', 'REPORT_FAILED')),
    CONSTRAINT ck_rni_source_error_code CHECK (
        (event_kind = 'REPORT_READY' AND source_error_code IS NULL) OR
        (event_kind = 'REPORT_FAILED' AND source_error_code IS NOT NULL AND
         CHAR_LENGTH(source_error_code) BETWEEN 1 AND 64)
    ),
    CONSTRAINT ck_rni_status CHECK (
        status IN ('PENDING', 'DISPATCHING', 'DELIVERED', 'SKIPPED_PREFERENCE',
                   'SKIPPED_PERMISSION', 'SKIPPED_SUPERSEDED', 'FAILED')
    ),
    CONSTRAINT ck_rni_attempt_budget CHECK (
        attempts <= max_attempts AND
        ((redrive_round = 0 AND max_attempts = 5) OR
         (redrive_round = 1 AND max_attempts = 10))
    ),
    CONSTRAINT ck_rni_unassigned_failure_only CHECK (
        recipient_id IS NOT NULL OR
        (event_kind = 'REPORT_FAILED' AND status = 'SKIPPED_PERMISSION')
    ),
    CONSTRAINT ck_rni_dispatch_lease_state CHECK (
        (status = 'DISPATCHING' AND dispatch_owner IS NOT NULL AND dispatch_lease_until IS NOT NULL) OR
        (status <> 'DISPATCHING' AND dispatch_owner IS NULL AND dispatch_lease_until IS NULL)
    ),
    CONSTRAINT ck_rni_delivery_link_state CHECK (
        (status = 'DELIVERED' AND notification_id IS NOT NULL AND delivered_at IS NOT NULL) OR
        (status <> 'DELIVERED' AND notification_id IS NULL AND delivered_at IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='报告通知持久化意图与投递状态';
