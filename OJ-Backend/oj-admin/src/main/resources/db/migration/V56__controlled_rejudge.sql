-- T8 受控重判：执行代次拆分 + 重判操作/条目表 + 画像快照过期标记 + 权限种子。
-- 决策：历史榜单/Rating 采用 A（冻结历史）→ 后续 D（版本化）→ 最后 C（链式重放）；
-- 本迁移只做单提交重判所需的基础结构，不重算任何历史成绩。

-- 1) judge_task：generation 只做执行代次；attempt_count 只做重试预算。
--    这样手动重判重置重试预算时不会破坏旧 Worker 的 CAS 保护。
ALTER TABLE judge_task
    ADD COLUMN generation INT UNSIGNED NOT NULL DEFAULT 0 AFTER attempt_count;

-- 2) 重判操作（一次请求一行）与条目（每条提交一行）。
CREATE TABLE rejudge_operation (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    operator_id BIGINT NOT NULL COMMENT '发起重判的管理员用户ID',
    operator_name VARCHAR(64) DEFAULT NULL COMMENT '发起人展示名（审计冗余）',
    reason VARCHAR(500) NOT NULL COMMENT '重判原因（必填）',
    scope_type VARCHAR(32) NOT NULL DEFAULT 'SINGLE_SUBMISSION' COMMENT 'SINGLE_SUBMISSION / ...',
    scope_json TEXT COMMENT '范围参数快照（JSON）',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/COMPLETED/FAILED/PARTIAL',
    total_items INT UNSIGNED NOT NULL DEFAULT 0,
    succeeded_items INT UNSIGNED NOT NULL DEFAULT 0,
    failed_items INT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at DATETIME DEFAULT NULL,
    finished_at DATETIME DEFAULT NULL,
    error_message VARCHAR(1000) DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_rejudge_operation_status (status),
    KEY idx_rejudge_operation_operator (operator_id),
    KEY idx_rejudge_operation_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='受控重判操作';

CREATE TABLE rejudge_item (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    operation_id BIGINT UNSIGNED NOT NULL,
    submission_id BIGINT NOT NULL,
    problem_id BIGINT DEFAULT NULL,
    user_id BIGINT DEFAULT NULL,
    contest_id BIGINT DEFAULT NULL,
    vp_attempt_id BIGINT DEFAULT NULL,
    testcase_digest CHAR(64) DEFAULT NULL COMMENT '重判时测试用例内容 SHA256，证明按哪一版用例执行',
    old_result_json TEXT COMMENT '重判前结果快照（JSON）',
    new_result_json TEXT COMMENT '重判后结果（JSON）',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at DATETIME DEFAULT NULL,
    error_message VARCHAR(1000) DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_rejudge_item_operation (operation_id),
    KEY idx_rejudge_item_submission_status (submission_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='受控重判条目';

-- 3) 画像快照过期标记：重判只标记，不做长时间事务内重建。
ALTER TABLE student_profile_snapshot
    ADD COLUMN stale TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1=重判后派生数据已过期，报告基线不可再使用';

-- 4) 权限码（控制器仍用 hasRole('ADMIN') 强制；权限码供后续 RBAC 细化）。
INSERT IGNORE INTO sys_permission (code, name) VALUES ('judge:rejudge', '重判提交');
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT 3, id FROM sys_permission WHERE code = 'judge:rejudge';
