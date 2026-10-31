-- T9-1/T9-2/T9-4：报告权限、job/delivery 状态机、外部通道 dry-run 基础。
-- 报告 HTML 为权威产物，存 report_job.html_content；不生成公开 URL。
CREATE TABLE report_job (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    student_id BIGINT NOT NULL,
    class_id BIGINT NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/GENERATING/GENERATED/FAILED',
    html_content LONGTEXT DEFAULT NULL,
    error_message VARCHAR(500) DEFAULT NULL,
    attempts INT NOT NULL DEFAULT 0,
    requester_id BIGINT DEFAULT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    finished_at DATETIME DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_job_period (student_id, class_id, period_start, period_end),
    KEY idx_report_job_class (class_id, create_time),
    KEY idx_report_job_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='周报生成任务';

CREATE TABLE report_delivery (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    job_id BIGINT NOT NULL,
    channel VARCHAR(16) NOT NULL COMMENT 'IN_APP/EMAIL/SMS/WECHAT',
    recipient_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DELIVERED/FAILED/DRY_RUN',
    attempts INT NOT NULL DEFAULT 0,
    error_message VARCHAR(500) DEFAULT NULL,
    delivered_at DATETIME DEFAULT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_delivery (job_id, channel, recipient_id),
    KEY idx_report_delivery_job (job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报告交付记录';

INSERT IGNORE INTO sys_permission (code, name) VALUES
    ('report:generate', '生成学习周报'),
    ('report:read', '查看学习周报');
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT 3, id FROM sys_permission WHERE code IN ('report:generate', 'report:read');
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT 2, id FROM sys_permission WHERE code IN ('report:generate', 'report:read');
