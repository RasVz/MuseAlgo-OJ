-- T10-1 比赛等级/参赛资格/报名审核基础结构。
-- 决策（2026-10-20）：正式赛按等级映射 Rating 范围（下限含、上限不含）；班级比赛不设限制；
-- 娱乐赛不计 Rating；任何比赛可开启报名审核；Rating 以报名时为准；已有比赛不追溯改变历史。

CREATE TABLE contest_level (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL COMMENT '等级编码，唯一',
    name VARCHAR(64) NOT NULL COMMENT '展示名，如 提高组',
    min_rating INT DEFAULT NULL COMMENT '允许的下限 Rating（含）；NULL=不限',
    max_rating INT DEFAULT NULL COMMENT '允许的上限 Rating（不含）；NULL=不限',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '0-停用 1-启用',
    sort_order INT NOT NULL DEFAULT 0,
    description VARCHAR(255) DEFAULT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_contest_level_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='比赛等级与Rating范围配置';

INSERT INTO contest_level (code, name, min_rating, max_rating, enabled, sort_order, description)
VALUES ('OPEN', '默认正式赛（不限Rating）', NULL, NULL, 1, 0, '未配置等级时的默认正式赛规则');

ALTER TABLE contest
    ADD COLUMN category VARCHAR(16) NOT NULL DEFAULT 'FORMAL' COMMENT 'FORMAL=计Rating正式赛 / ENTERTAINMENT=不计Rating娱乐赛' AFTER mode,
    ADD COLUMN level_code VARCHAR(32) DEFAULT NULL COMMENT '比赛等级编码（班级比赛/娱乐赛可为空）' AFTER category,
    ADD COLUMN registration_review TINYINT(1) NOT NULL DEFAULT 0 COMMENT '报名是否需要审核' AFTER level_code,
    ADD COLUMN rating_min INT DEFAULT NULL COMMENT '资格下限（含）快照；NULL=不限' AFTER registration_review,
    ADD COLUMN rating_max INT DEFAULT NULL COMMENT '资格上限（不含）快照；NULL=不限' AFTER rating_min;

ALTER TABLE contest_user
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'AUTO_APPROVED' COMMENT 'AUTO_APPROVED/PENDING/APPROVED/REJECTED' AFTER user_id,
    ADD COLUMN rating_at_registration INT DEFAULT NULL COMMENT '报名时Rating快照' AFTER status,
    ADD COLUMN eligible TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否满足该场资格（1-正式选手 0-超限打星号）' AFTER rating_at_registration,
    ADD COLUMN reviewed_by BIGINT DEFAULT NULL COMMENT '审核人ID' AFTER eligible,
    ADD COLUMN review_time DATETIME DEFAULT NULL AFTER reviewed_by,
    ADD COLUMN reject_reason VARCHAR(255) DEFAULT NULL AFTER review_time,
    ADD KEY idx_contest_user_status (contest_id, status);

ALTER TABLE contest_rank
    ADD COLUMN participant_type VARCHAR(16) NOT NULL DEFAULT 'OFFICIAL' COMMENT 'OFFICIAL/STARRED' AFTER total_score;
