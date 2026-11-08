"""Reference-role regression fixtures on disposable databases owned by operations_drill."""
from pathlib import Path
import secrets
import subprocess


BASE_PERMISSIONS = """
INSERT INTO sys_permission(code,name) VALUES
 ('problem:manage','problem'),('contest:manage','contest'),
 ('class:member:manage','class members'),('submission:read:private','private submissions');
"""
FEATURE_PERMISSIONS = """
INSERT INTO sys_permission(code,name) VALUES
 ('judge:rejudge','rejudge'),('report:read','read'),('report:generate','generate');
"""
FEATURE_GRANTS = """
INSERT INTO sys_role_permission(role_id,permission_id)
 SELECT 3,id FROM sys_permission WHERE code='judge:rejudge';
INSERT INTO sys_role_permission(role_id,permission_id)
 SELECT 3,id FROM sys_permission WHERE code IN ('report:read','report:generate');
INSERT INTO sys_role_permission(role_id,permission_id)
 SELECT 2,id FROM sys_permission WHERE code IN ('report:read','report:generate');
"""
DEFAULT_ROLE_CODES = """INSERT INTO sys_role(id,code,name) VALUES
 (1,'STUDENT','student'),(2,'TEACHER','teacher'),(3,'ADMIN','admin');"""


def check_reference_data(sql, root):
    baseline = (root / 'docker/mysql-init/00-schema.sql').read_text()
    initializer = (root / 'docker/mysql-init/01-reference-roles.sql').read_text()
    migration = (root / 'OJ-Backend/oj-admin/src/main/resources/db/migration/V63__bootstrap_reference_roles.sql').read_text()
    databases = []

    def fixture():
        name = 'reference_' + secrets.token_hex(6)
        sql('CREATE DATABASE ' + name + ';')
        databases.append(name)
        sql('USE ' + name + ';' + baseline)
        return lambda query: sql('USE ' + name + ';' + query)

    def standard_roles(run):
        run(DEFAULT_ROLE_CODES)

    def roles(run):
        return run("SELECT GROUP_CONCAT(CONCAT(id,':',code) ORDER BY id) FROM sys_role WHERE id IN (1,2,3);")

    try:
        # New Docker volume: init data exists before V56/V60 assign feature permissions.
        fresh = fixture()
        fresh(initializer)
        assert roles(fresh) == '1:STUDENT,2:TEACHER,3:ADMIN'
        assert fresh('SELECT COUNT(*) FROM sys_permission WHERE code IN '
                     "('problem:manage','contest:manage','class:member:manage','submission:read:private');") == '4'
        assert fresh('SELECT COUNT(*) FROM sys_role_permission WHERE role_id=3;') == '4'
        assert fresh('SELECT COUNT(*) FROM sys_role_permission WHERE role_id=2;') == '3'
        assert fresh('SELECT COUNT(*) FROM sys_role_permission WHERE role_id=1;') == '0'
        fresh(FEATURE_PERMISSIONS)
        # These represent the grants made by V56 and V60 before V63 runs.
        fresh(FEATURE_GRANTS)
        assert fresh('SELECT COUNT(*) FROM sys_role_permission WHERE role_id=3;') == '7'
        assert fresh('SELECT COUNT(*) FROM sys_role_permission WHERE role_id=2;') == '5'
        fresh(migration)
        assert fresh('SELECT COUNT(*) FROM sys_role_permission WHERE role_id=3;') == '7'
        assert fresh('SELECT COUNT(*) FROM sys_role_permission WHERE role_id=2;') == '5'
        assert fresh('SELECT COUNT(*) FROM user;') == '0'

        # Old V53 structure-only database: migrations V56/V60 ran without role rows.
        empty = fixture()
        assert empty('SELECT COUNT(*) FROM sys_role;') == '0'
        empty(FEATURE_PERMISSIONS)
        empty(migration)
        assert roles(empty) == '1:STUDENT,2:TEACHER,3:ADMIN'
        assert empty('SELECT COUNT(*) FROM user;') == '0'
        assert empty('SELECT COUNT(*) FROM sys_role_permission WHERE role_id=3;') == '7'
        assert empty('SELECT COUNT(*) FROM sys_role_permission WHERE role_id=2;') == '5'
        # The migration's normal execution is once; direct rerun remains harmless for these rows.
        empty(migration)
        assert empty('SELECT COUNT(*) FROM sys_role_permission WHERE role_id=3;') == '7'

        # Existing roles with a deliberately reduced permission set stay reduced.
        standard = fixture()
        standard_roles(standard)
        standard("INSERT INTO sys_role(id,code,name) VALUES (8,'CUSTOM','custom');")
        standard(BASE_PERMISSIONS + FEATURE_PERMISSIONS)
        standard("INSERT INTO sys_role_permission(role_id,permission_id) "
                 "SELECT 3,id FROM sys_permission WHERE code='report:generate';"
                 "INSERT INTO sys_role_permission(role_id,permission_id) "
                 "SELECT 8,id FROM sys_permission WHERE code='problem:manage';")
        standard(migration)
        assert standard('SELECT COUNT(*) FROM sys_role_permission WHERE role_id=3;') == '1'
        assert standard("SELECT COUNT(*) FROM sys_role_permission rp JOIN sys_permission p ON p.id=rp.permission_id "
                        "WHERE rp.role_id=3 AND p.code='report:read';") == '0'
        assert standard('SELECT COUNT(*) FROM sys_role_permission WHERE role_id=8;') == '1'

        # A missing STUDENT row must not cause existing ADMIN/TEACHER grants to be repopulated.
        partial = fixture()
        partial("INSERT INTO sys_role(id,code,name) VALUES (2,'TEACHER','teacher'),(3,'ADMIN','admin');")
        partial(BASE_PERMISSIONS + FEATURE_PERMISSIONS)
        partial("INSERT INTO sys_role_permission(role_id,permission_id) "
                "SELECT 3,id FROM sys_permission WHERE code='judge:rejudge';"
                "INSERT INTO sys_role_permission(role_id,permission_id) "
                "SELECT 2,id FROM sys_permission WHERE code='report:read';")
        partial(migration)
        assert roles(partial) == '1:STUDENT,2:TEACHER,3:ADMIN'
        assert partial('SELECT COUNT(*) FROM sys_role_permission WHERE role_id=3;') == '1'
        assert partial('SELECT COUNT(*) FROM sys_role_permission WHERE role_id=2;') == '1'
        assert partial('SELECT COUNT(*) FROM sys_role_permission WHERE role_id=1;') == '0'

        # An absent permission catalogue is repaired without interpreting it as a grant request.
        no_catalog = fixture()
        standard_roles(no_catalog)
        no_catalog(migration)
        assert no_catalog("SELECT COUNT(*) FROM sys_permission WHERE code IN "
                          "('problem:manage','contest:manage','class:member:manage','submission:read:private');") == '4'
        assert no_catalog('SELECT COUNT(*) FROM sys_role_permission WHERE role_id IN (2,3);') == '0'

        # Known old demo reversal: preserve user role meaning and keep admin-only grants off STUDENT.
        legacy = fixture()
        legacy("INSERT INTO sys_role(id,code,name) VALUES "
               "(1,'admin','admin'),(2,'teacher','teacher'),(3,'student','student'),"
               "(8,'_oj_tmp_1_0','custom one'),(9,'_oj_tmp_3_0','custom three');"
               "INSERT INTO user(id,username,password,nickname,role) VALUES "
               "(1001,'legacy_admin','synthetic','legacy admin',3),"
               "(1002,'legacy_student','synthetic','legacy student',1);"
               "INSERT INTO sys_user_role VALUES (1001,1),(1002,3);")
        legacy(BASE_PERMISSIONS + FEATURE_PERMISSIONS +
               "INSERT INTO sys_permission(code,name) VALUES ('problem:view','view');")
        legacy("INSERT INTO sys_role_permission(role_id,permission_id) "
               "SELECT 1,id FROM sys_permission WHERE code IN "
               "('contest:manage','class:member:manage','submission:read:private');"
               "INSERT INTO sys_role_permission(role_id,permission_id) "
               "SELECT 3,id FROM sys_permission WHERE code IN ('problem:view','judge:rejudge','report:read','report:generate');")
        legacy(migration)
        assert roles(legacy) == '1:STUDENT,2:TEACHER,3:ADMIN'
        assert legacy('SELECT role_id FROM sys_user_role WHERE user_id=1001;') == '3'
        assert legacy('SELECT role_id FROM sys_user_role WHERE user_id=1002;') == '1'
        assert legacy('SELECT role FROM user WHERE id=1001;') == '3'
        assert legacy('SELECT role FROM user WHERE id=1002;') == '1'
        assert legacy("SELECT COUNT(*) FROM sys_role_permission rp JOIN sys_permission p ON p.id=rp.permission_id "
                      "WHERE rp.role_id=1 AND p.code IN ('judge:rejudge','report:read','report:generate');") == '0'
        assert legacy("SELECT COUNT(*) FROM sys_role_permission rp JOIN sys_permission p ON p.id=rp.permission_id "
                      "WHERE rp.role_id=3 AND p.code IN ('judge:rejudge','report:read','report:generate');") == '3'
        assert legacy("SELECT COUNT(*) FROM sys_role_permission rp JOIN sys_permission p ON p.id=rp.permission_id "
                      "WHERE rp.role_id=1 AND p.code='problem:view';") == '1'
        assert legacy("SELECT COUNT(*) FROM sys_role_permission rp JOIN sys_permission p ON p.id=rp.permission_id "
                      "WHERE rp.role_id=3 AND p.code='problem:manage';") == '0'
        assert legacy("SELECT COUNT(*) FROM sys_role_permission rp JOIN sys_permission p ON p.id=rp.permission_id "
                      "WHERE rp.role_id=3 AND p.code='contest:manage';") == '1'
        assert legacy("SELECT GROUP_CONCAT(code ORDER BY id) FROM sys_role WHERE id IN (8,9);") == '_oj_tmp_1_0,_oj_tmp_3_0'

        # Unknown IDs and known codes attached to conflicting IDs fail without data changes.
        unknown = fixture()
        unknown("INSERT INTO sys_role(id,code,name) VALUES (1,'OWNER','owner');")
        try:
            unknown(migration)
        except subprocess.CalledProcessError:
            pass
        else:
            raise AssertionError('Unknown role mapping must fail closed')
        assert unknown('SELECT code FROM sys_role WHERE id=1;') == 'OWNER'
        assert unknown('SELECT COUNT(*) FROM sys_role;') == '1'

        conflicting = fixture()
        conflicting("INSERT INTO sys_role(id,code,name) VALUES (8,'STUDENT','unexpected');")
        try:
            conflicting(migration)
        except subprocess.CalledProcessError:
            pass
        else:
            raise AssertionError('Built-in role code on an unexpected ID must fail closed')
        assert conflicting('SELECT id FROM sys_role WHERE code=\'STUDENT\';') == '8'
        assert conflicting('SELECT COUNT(*) FROM sys_role;') == '1'

        return {
            'fresh_volume_initializer': True,
            'empty_legacy_role_tables': True,
            'existing_grant_revocations_preserved': True,
            'partial_role_missing_does_not_regrant': True,
            'missing_permission_catalog_is_not_a_grant_request': True,
            'legacy_demo_semantics_preserved': True,
            'legacy_admin_only_grants_remapped': True,
            'unknown_and_conflicting_mappings_rejected_unchanged': True,
            'demo_accounts_not_created': True,
        }
    finally:
        for name in databases:
            sql('DROP DATABASE ' + name + ';')
