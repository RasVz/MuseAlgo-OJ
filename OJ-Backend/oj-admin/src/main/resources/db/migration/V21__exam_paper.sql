-- ============================================================
-- 训练试卷（训练 tab：GESP/CSP 真题套卷等，独立于题单/课程/题库）
-- ============================================================

CREATE TABLE IF NOT EXISTS exam_paper (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    title            VARCHAR(200) NOT NULL COMMENT '试卷标题，如 GESP 2级 2024-09 真题',
    description      TEXT         NULL COMMENT '试卷说明 / 备考建议',
    category         VARCHAR(32)  NOT NULL DEFAULT 'MOCK' COMMENT 'GESP / CSP_J / CSP_S / NOIP / MOCK',
    level            VARCHAR(32)  NULL COMMENT '等级/组别，如 GESP 2级、CSP-J',
    duration_minutes INT          NOT NULL DEFAULT 120 COMMENT '建议作答时长（分钟）',
    total_score      INT          NOT NULL DEFAULT 100 COMMENT '试卷满分',
    problem_count    INT          NOT NULL DEFAULT 0 COMMENT '题目数（冗余，随题目变更刷新）',
    is_published     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '0=草稿，1=发布（训练 tab 可见）',
    create_by        BIGINT       NULL,
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted       TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_category (category),
    KEY idx_published (is_published)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='训练试卷';

CREATE TABLE IF NOT EXISTS exam_paper_problem (
    id            BIGINT   NOT NULL AUTO_INCREMENT,
    paper_id      BIGINT   NOT NULL,
    problem_id    BIGINT   NOT NULL,
    problem_order INT      NOT NULL DEFAULT 0 COMMENT '题号，从 1 开始',
    score         INT      NOT NULL DEFAULT 0 COMMENT '该题分值',
    create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_paper_problem (paper_id, problem_id),
    KEY idx_problem (problem_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='试卷-题目关联';
