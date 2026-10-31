-- ============================================================
-- 虚拟赛（VP）会话：错过比赛按真实规则补打
-- ============================================================

CREATE TABLE IF NOT EXISTS vp_attempt (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    contest_id         BIGINT      NOT NULL,
    user_id            BIGINT      NOT NULL,
    status             VARCHAR(16) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING / FINISHED',
    start_time         DATETIME    NOT NULL,
    end_time           DATETIME    NOT NULL COMMENT 'start + contest.duration',
    submit_time        DATETIME    NULL,
    score              INT         NOT NULL DEFAULT 0,
    rank_num           INT         NULL,
    total_participants INT         NULL,
    create_time        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_contest_user (contest_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='虚拟赛作答会话';
