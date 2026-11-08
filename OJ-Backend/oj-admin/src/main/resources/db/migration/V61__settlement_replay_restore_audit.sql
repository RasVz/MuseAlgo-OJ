-- 依赖 V59：保留原始迁移校验和；v2 备份增加流水/结算版本与重放后指纹，恢复留审计。
ALTER TABLE contest_settlement_replay
    ADD COLUMN restored_by BIGINT DEFAULT NULL,
    ADD COLUMN restored_at DATETIME DEFAULT NULL,
    ADD COLUMN restore_reason VARCHAR(500) DEFAULT NULL,
    MODIFY COLUMN status VARCHAR(16) NOT NULL COMMENT 'RUNNING/COMPLETED/FAILED/RESTORED';
