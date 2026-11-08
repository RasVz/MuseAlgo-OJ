-- 为V53纯结构基线补齐基础角色。
-- V1～V62历史迁移与docker/mysql-init/00-schema.sql保持冻结。
-- 保留已有授权；仅为本迁移新建的角色授予默认权限。
DELIMITER $$
CREATE PROCEDURE bootstrap_reference_roles()
BEGIN
    DECLARE legacy_demo BOOLEAN DEFAULT FALSE;
    DECLARE create_teacher BOOLEAN DEFAULT FALSE;
    DECLARE create_admin BOOLEAN DEFAULT FALSE;
    DECLARE temporary_role_1_code VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    DECLARE temporary_role_3_code VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    DECLARE temporary_code_suffix INT DEFAULT 0;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;

    START TRANSACTION;

    -- 旧演示种子使用1=ADMIN、3=STUDENT。仅修复这一种已知映射，其他异常须人工检查。
    SET legacy_demo = EXISTS(SELECT 1 FROM sys_role WHERE id=1 AND UPPER(code)='ADMIN')
                      AND EXISTS(SELECT 1 FROM sys_role WHERE id=3 AND UPPER(code)='STUDENT');

    IF EXISTS(SELECT 1 FROM sys_role WHERE
        (id=1 AND UPPER(code)<>'STUDENT' AND NOT (legacy_demo AND UPPER(code)='ADMIN')) OR
        (id=2 AND UPPER(code)<>'TEACHER') OR
        (id=3 AND UPPER(code)<>'ADMIN' AND NOT (legacy_demo AND UPPER(code)='STUDENT')) OR
        (UPPER(code) IN ('STUDENT','TEACHER','ADMIN') AND
            NOT ((id=1 AND UPPER(code) IN ('STUDENT','ADMIN')) OR
                 (id=2 AND UPPER(code)='TEACHER') OR
                 (id=3 AND UPPER(code) IN ('STUDENT','ADMIN'))))
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='内置角色映射未知或冲突，请检查sys_role后再升级';
    END IF;

    SET create_teacher = NOT EXISTS(SELECT 1 FROM sys_role WHERE id=2 AND UPPER(code)='TEACHER');
    SET create_admin = NOT legacy_demo AND
        NOT EXISTS(SELECT 1 FROM sys_role WHERE id=3 AND UPPER(code)='ADMIN');

    IF legacy_demo THEN
        -- 保持用户实际角色含义。将V56/V60误授给旧学生角色的管理员权限移至纠正后的ADMIN。
        -- 临时code避开管理员自定义角色已占用的命名空间，再安全交换1和3的唯一code。
        SET temporary_role_1_code=CONCAT('_oj_tmp_1_',temporary_code_suffix);
        WHILE EXISTS(SELECT 1 FROM sys_role WHERE code=temporary_role_1_code) DO
            SET temporary_code_suffix=temporary_code_suffix+1;
            SET temporary_role_1_code=CONCAT('_oj_tmp_1_',temporary_code_suffix);
        END WHILE;
        SET temporary_code_suffix=0;
        SET temporary_role_3_code=CONCAT('_oj_tmp_3_',temporary_code_suffix);
        WHILE EXISTS(SELECT 1 FROM sys_role WHERE code=temporary_role_3_code) DO
            SET temporary_code_suffix=temporary_code_suffix+1;
            SET temporary_role_3_code=CONCAT('_oj_tmp_3_',temporary_code_suffix);
        END WHILE;
        DROP TEMPORARY TABLE IF EXISTS tmp_reference_user_roles;
        DROP TEMPORARY TABLE IF EXISTS tmp_reference_grants;
        CREATE TEMPORARY TABLE tmp_reference_user_roles AS
            SELECT user_id, 4-role_id AS role_id FROM sys_user_role WHERE role_id IN (1,3);
        CREATE TEMPORARY TABLE tmp_reference_grants AS
            SELECT CASE
                       WHEN rp.role_id=1 THEN 3
                       WHEN p.code IN ('judge:rejudge','report:generate','report:read') THEN 3
                       ELSE 1
                   END AS role_id,
                   rp.permission_id
            FROM sys_role_permission rp
            JOIN sys_permission p ON p.id=rp.permission_id
            WHERE rp.role_id IN (1,3);

        DELETE FROM sys_user_role WHERE role_id IN (1,3);
        DELETE FROM sys_role_permission WHERE role_id IN (1,3);
        UPDATE sys_role SET code=temporary_role_1_code WHERE id=1;
        UPDATE sys_role SET code=temporary_role_3_code WHERE id=3;
        UPDATE sys_role SET code=CASE id WHEN 1 THEN 'STUDENT' WHEN 2 THEN 'TEACHER' ELSE 'ADMIN' END
            WHERE id IN (1,2,3);
        UPDATE sys_role SET name=CASE id WHEN 1 THEN '学生' ELSE '管理员' END WHERE id IN (1,3);
        INSERT INTO sys_user_role(user_id,role_id) SELECT user_id,role_id FROM tmp_reference_user_roles;
        INSERT IGNORE INTO sys_role_permission(role_id,permission_id)
            SELECT role_id,permission_id FROM tmp_reference_grants;
        DROP TEMPORARY TABLE tmp_reference_user_roles;
        DROP TEMPORARY TABLE tmp_reference_grants;
    END IF;

    INSERT IGNORE INTO sys_role(id,code,name) VALUES
        (1,'STUDENT','学生'),(2,'TEACHER','教师'),(3,'ADMIN','管理员');

    IF (SELECT COUNT(*) FROM sys_role WHERE
        (id=1 AND UPPER(code)='STUDENT') OR
        (id=2 AND UPPER(code)='TEACHER') OR
        (id=3 AND UPPER(code)='ADMIN'))<>3 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='内置角色ID或code冲突，请检查sys_role后再升级';
    END IF;

    UPDATE sys_role SET code=UPPER(code) WHERE id IN (1,2,3);
    UPDATE sys_role SET name=CASE id WHEN 1 THEN '学生' WHEN 2 THEN '教师' ELSE '管理员' END
        WHERE id IN (1,2,3);

    -- 权限ID因安装数据而异，按权限码补目录和授权。
    INSERT IGNORE INTO sys_permission(code,name) VALUES
        ('problem:manage','管理题目和测试数据'),
        ('contest:manage','管理比赛'),
        ('class:member:manage','管理班级成员'),
        ('submission:read:private','读取私有提交详情');

    -- 迁移前已存在的角色可能包含有意撤权；仅为本迁移新建的ADMIN或TEACHER补默认授权。
    IF create_admin THEN
        INSERT IGNORE INTO sys_role_permission(role_id,permission_id)
            SELECT 3,id FROM sys_permission WHERE code IN
                ('problem:manage','contest:manage','class:member:manage',
                 'submission:read:private','judge:rejudge','report:generate','report:read');
    END IF;
    IF create_teacher THEN
        INSERT IGNORE INTO sys_role_permission(role_id,permission_id)
            SELECT 2,id FROM sys_permission WHERE code IN
                ('problem:manage','contest:manage','class:member:manage','report:generate','report:read');
    END IF;

    COMMIT;
END$$
CALL bootstrap_reference_roles()$$
DROP PROCEDURE bootstrap_reference_roles$$
DELIMITER ;
