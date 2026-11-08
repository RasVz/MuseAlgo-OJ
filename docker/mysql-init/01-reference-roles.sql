-- 仅用于 MySQL 空数据卷初始化：先建立基础角色和V1原有权限，避免V56/V60授权时外键缺角色。
-- 本文件不创建账号或业务数据；已有库升级由Flyway负责，不要手工对既有库重放本文件。
-- 标准ID来自V1：1学生、2教师、3管理员；演示数据也须遵守此约定。
INSERT INTO sys_role(id,code,name) VALUES (1,'STUDENT','学生'),(2,'TEACHER','教师'),(3,'ADMIN','管理员');
INSERT INTO sys_permission(code,name) VALUES
    ('problem:manage','管理题目和测试数据'),
    ('contest:manage','管理比赛'),
    ('class:member:manage','管理班级成员'),
    ('submission:read:private','读取私有提交详情');
INSERT INTO sys_role_permission(role_id,permission_id)
    SELECT 3,id FROM sys_permission;
INSERT INTO sys_role_permission(role_id,permission_id)
    SELECT 2,id FROM sys_permission WHERE code IN ('problem:manage','contest:manage','class:member:manage');
