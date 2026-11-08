-- T8-3 C：结算链式重放入口与审计/备份记录。
-- 仅管理员、显式确认后执行；执行在单事务内完成，失败整体回滚。
CREATE TABLE contest_settlement_replay (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    from_contest_id BIGINT NOT NULL COMMENT '起点比赛ID',
    operator_id BIGINT DEFAULT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(16) NOT NULL COMMENT 'RUNNING/COMPLETED/FAILED',
    total_contests INT NOT NULL DEFAULT 0,
    affected_users INT NOT NULL DEFAULT 0,
    backup_json LONGTEXT DEFAULT NULL COMMENT '执行前 user.rating 与 contest_rank 快照（用于核对/恢复）',
    started_at DATETIME NOT NULL,
    finished_at DATETIME DEFAULT NULL,
    error_message VARCHAR(500) DEFAULT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_replay_from (from_contest_id),
    KEY idx_replay_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='比赛结算链式重放记录';
