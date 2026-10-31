-- ============================================================
-- 训练试卷作答会话（限时）：进入试卷开始计时，到时自动交卷
-- 客观题得分 = 该卷该用户最新作答判分之和
-- 编程题得分 = 会话时间窗内每题最好成绩之和（经 oj-api 契约查 submission）
-- ============================================================

CREATE TABLE IF NOT EXISTS exam_attempt (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    paper_id        BIGINT      NOT NULL,
    user_id         BIGINT      NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING / SUBMITTED / TIMEOUT',
    start_time      DATETIME    NOT NULL,
    end_time        DATETIME    NOT NULL COMMENT 'start_time + duration；到时自动交卷',
    submit_time     DATETIME    NULL,
    objective_score INT         NOT NULL DEFAULT 0 COMMENT '客观题得分',
    coding_score    INT         NOT NULL DEFAULT 0 COMMENT '编程题得分',
    total_score     INT         NOT NULL DEFAULT 0,
    create_time     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_paper_user (paper_id, user_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='训练试卷作答会话';
