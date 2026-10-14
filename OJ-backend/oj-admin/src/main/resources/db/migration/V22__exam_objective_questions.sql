-- ============================================================
-- 训练试卷客观题：单选 / 多选 / 判断，提交时即时判分
-- ============================================================

CREATE TABLE IF NOT EXISTS exam_question (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    paper_id       BIGINT       NOT NULL,
    question_type  VARCHAR(16)  NOT NULL COMMENT 'SINGLE / MULTIPLE / JUDGE',
    statement      TEXT         NOT NULL COMMENT '题干',
    options        JSON         NULL COMMENT '选项 [{key,text}]；判断题可为空',
    answer         VARCHAR(255) NOT NULL COMMENT '标准答案：A / ABD / T / F',
    score          INT          NOT NULL DEFAULT 0,
    question_order INT          NOT NULL DEFAULT 0,
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_paper (paper_id, question_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='试卷客观题';

CREATE TABLE IF NOT EXISTS exam_answer (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    paper_id    BIGINT       NOT NULL,
    question_id BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    answer      VARCHAR(255) NULL COMMENT '学生作答',
    is_correct  TINYINT(1)   NOT NULL DEFAULT 0,
    score       INT          NOT NULL DEFAULT 0,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_answer (paper_id, question_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客观题作答与判分';
