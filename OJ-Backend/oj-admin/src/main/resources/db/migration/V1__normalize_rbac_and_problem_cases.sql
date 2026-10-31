-- MySQL 8.0 migration. Back up the database before enabling OJ_FLYWAY_ENABLED.
-- The legacy user.role column remains until OJ_NORMALIZED_RBAC is enabled everywhere.

CREATE TABLE IF NOT EXISTS sys_role (
    id TINYINT UNSIGNED NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_role_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_permission (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(96) NOT NULL,
    name VARCHAR(128) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_permission_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_user_role (
    user_id BIGINT NOT NULL,            -- 与 user.id 类型一致（BIGINT 有符号）
    role_id TINYINT UNSIGNED NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_sys_user_role_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    CONSTRAINT fk_sys_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_role_permission (
    role_id TINYINT UNSIGNED NOT NULL,
    permission_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_sys_role_permission_role FOREIGN KEY (role_id) REFERENCES sys_role (id),
    CONSTRAINT fk_sys_role_permission_permission FOREIGN KEY (permission_id) REFERENCES sys_permission (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO sys_role (id, code, name) VALUES
    (1, 'STUDENT', '学生'), (2, 'TEACHER', '教师'), (3, 'ADMIN', '管理员');

INSERT IGNORE INTO sys_permission (code, name) VALUES
    ('problem:manage', '管理题目和测试数据'),
    ('contest:manage', '管理比赛'),
    ('class:member:manage', '管理班级成员'),
    ('submission:read:private', '读取私有提交详情');

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT 3, id FROM sys_permission;

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT 2, id FROM sys_permission
WHERE code IN ('problem:manage', 'contest:manage', 'class:member:manage');

INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT id, CASE WHEN role IN (1, 2, 3) THEN role ELSE 1 END FROM `user`;

-- MySQL 不支持 ADD COLUMN IF NOT EXISTS；Flyway 保证每个迁移只执行一次，
-- 因此直接使用 ADD COLUMN 即可，无需 IF NOT EXISTS。
ALTER TABLE problem_case
    ADD COLUMN case_order INT UNSIGNED NOT NULL DEFAULT 1 AFTER problem_id,
    ADD COLUMN case_type TINYINT UNSIGNED NOT NULL DEFAULT 0 AFTER score,
    ADD INDEX idx_problem_case_problem_order (problem_id, case_order),
    ADD CONSTRAINT chk_problem_case_type CHECK (case_type IN (0, 1));
