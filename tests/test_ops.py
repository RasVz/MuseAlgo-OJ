import argparse
import importlib.util
import json
import os
import subprocess
from pathlib import Path
import tarfile
import tempfile
import unittest
from contextlib import nullcontext
from unittest.mock import patch

SPEC=importlib.util.spec_from_file_location('musealgo_ops',Path(__file__).resolve().parents[1]/'scripts/ops.py')
OPS=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(OPS)
MIGRATIONS=OPS.ROOT/'OJ-Backend/oj-admin/src/main/resources/db/migration'
LATEST_SCHEMA=max(int(path.name.split('__',1)[0][1:]) for path in MIGRATIONS.glob('V*__*.sql'))
PREVIOUS_SCHEMA=LATEST_SCHEMA-1


class BackupValidationTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory()
        self.root=Path(self.temporary.name)
        self.args=argparse.Namespace(env_file='.env',compose='docker-compose.prod.yml',project='musealgo-recovery-test',source=str(self.root),confirm_empty_target=True)
        self.operations=OPS.Operations(self.args)
        (self.root/'database.sql').write_text('SELECT 1;')
        for name in OPS.VOLUMES:
            with tarfile.open(self.root/(name+'.tar.gz'),'w:gz'):
                pass
        self.manifest()
    def tearDown(self):
        self.temporary.cleanup()
    def manifest(self):
        files={p.name:OPS.sha256(p) for p in self.root.iterdir() if p.name!='manifest.json'}
        (self.root/'manifest.json').write_text(json.dumps({'format':1,'files':files}))
    def test_all_four_archives_must_match_checksums(self):
        self.assertEqual(4,len(self.operations.verify(self.root)['files']))
        (self.root/'database.sql').write_text('modified')
        with self.assertRaisesRegex(ValueError,'checksum mismatch'):
            self.operations.verify(self.root)
    def test_traversal_member_is_rejected_even_with_valid_checksum(self):
        with tarfile.open(self.root/'uploads.tar.gz','w:gz') as archive:
            archive.addfile(tarfile.TarInfo('../../escape'))
        self.manifest()
        with self.assertRaisesRegex(ValueError,'archive member'):
            self.operations.verify(self.root)
    def test_restore_refuses_existing_project_volumes_before_any_write(self):
        ids={'migrate':'sha256:'+'a'*64,'backend':'sha256:'+'a'*64,'frontend':'sha256:'+'b'*64}
        with patch.object(self.operations,'resolve_image_ids',return_value=ids),patch.object(self.operations,'state',return_value=[]),patch.object(self.operations,'compose') as compose:
            with patch.object(OPS.subprocess,'run') as run:
                run.return_value.stdout=b'existing-volume\n'
                with self.assertRaisesRegex(ValueError,'already has volumes'):
                    self.operations.restore()
            compose.assert_not_called()

    def test_skip_migrations_requires_the_backup_application_image_pair(self):
        args=self.args
        args.skip_migrations=True
        source_ids={'migrate':'sha256:'+'c'*64,'backend':'sha256:'+'c'*64,'frontend':'sha256:'+'d'*64}
        manifest=json.loads((self.root/'manifest.json').read_text())
        manifest['images']=source_ids
        (self.root/'manifest.json').write_text(json.dumps(manifest))
        target_ids={'migrate':'sha256:'+'a'*64,'backend':'sha256:'+'a'*64,'frontend':'sha256:'+'b'*64}
        with patch.object(self.operations,'resolve_image_ids',return_value=target_ids),patch.object(self.operations,'compose') as compose:
            with self.assertRaisesRegex(ValueError,'同一组应用镜像'):
                self.operations.restore()
        compose.assert_not_called()
    def test_corrupt_backup_is_rejected_before_docker_access(self):
        (self.root/'database.sql').write_text('modified')
        with patch.object(self.operations,'compose') as compose,patch.object(self.operations,'state') as state:
            with self.assertRaisesRegex(ValueError,'checksum mismatch'):
                self.operations.restore()
            compose.assert_not_called();state.assert_not_called()

    def test_missing_or_malformed_manifest_is_a_controlled_corruption_error(self):
        (self.root/'manifest.json').write_text('[]')
        with self.assertRaisesRegex(ValueError,'manifest'):
            self.operations.verify(self.root)

    def test_restore_migration_failure_stops_services_and_preserves_verified_target(self):
        self._assert_restore_failure('migration','database migration')

    def test_restore_startup_failure_stops_services_and_preserves_verified_target(self):
        self._assert_restore_failure('startup','application startup and health check')

    def test_restore_database_startup_failure_reports_partial_project_resources(self):
        self._assert_restore_failure('database-startup','database/cache startup')

    def _assert_restore_failure(self, failure_stage, expected_phase):
        self.args.skip_migrations=False
        image_ids={'migrate':'sha256:'+'a'*64,'backend':'sha256:'+'a'*64,
                   'frontend':'sha256:'+'b'*64}
        compose_calls=[]
        def compose(*args,**kwargs):
            compose_calls.append(args)
            if failure_stage=='database-startup' and args[:1]==('up',) and 'mysql' in args:
                raise subprocess.CalledProcessError(1,'synthetic database startup failure')
            if failure_stage=='migration' and args[:1]==('run',) and 'migrate' in args:
                raise subprocess.CalledProcessError(1,'synthetic migration failure')
            if failure_stage=='startup' and args[:1]==('up',) and 'backend' in args:
                raise subprocess.CalledProcessError(1,'synthetic startup failure')
            return subprocess.CompletedProcess(args,0,stdout=b'')
        with patch.object(self.operations,'resolve_image_ids',return_value=image_ids), \
             patch.object(self.operations,'use_image_ids',return_value=nullcontext()), \
             patch.object(self.operations,'state',return_value=[]), \
             patch.object(self.operations,'sql',side_effect=lambda query=None,**kwargs:'0' if query=='SELECT COUNT(*) FROM user;' else ''), \
             patch.object(self.operations,'volume'), \
             patch.object(self.operations,'compose',side_effect=compose), \
             patch.object(OPS.subprocess,'run',return_value=subprocess.CompletedProcess([],0,stdout=b'')):
            with self.assertRaisesRegex(ValueError,expected_phase) as raised:
                self.operations.restore()
        message=str(raised.exception)
        self.assertIn(str(self.root),message)
        self.assertIn('已校验并保留',message)
        self.assertIn('目标项目与数据卷保留',message)
        self.assertIn('新的Compose项目名重试',message)
        self.assertEqual(('stop','--timeout','60','frontend','backend'),compose_calls[-1])
        self.assertFalse(any(call[:1]==('down',) for call in compose_calls))

class DatabaseCredentialBoundaryTest(unittest.TestCase):
    def test_selected_env_file_is_not_overridden_by_shell_credentials(self):
        args=argparse.Namespace(env_file='.env',compose='docker-compose.prod.yml',project='isolated')
        with patch.dict(os.environ,{'OJ_DB_PASS':'foreign-password','BACKEND_PORT':'9999','PATH':'preserved-path'}):
            operations=OPS.Operations(args)
        with patch.object(OPS.subprocess,'run') as run:
            operations.compose('ps')
        selected_env=run.call_args.kwargs['env']
        self.assertNotIn('OJ_DB_PASS',selected_env)
        self.assertNotIn('BACKEND_PORT',selected_env)
        self.assertEqual('preserved-path',selected_env['PATH'])

    def check_rejected(self, app, migration, root, message):
        env=os.environ.copy();env.update(OJ_DB_PASS=app,MYSQL_PASSWORD=migration,MYSQL_ROOT_PASSWORD=root)
        result=subprocess.run(['bash',str(Path(__file__).resolve().parents[1]/'deploy/mysql-users.sh')],
                              env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        self.assertEqual(1,result.returncode)
        self.assertIn(message,result.stderr.decode())
    def test_reused_root_password_is_rejected_before_database_access(self):
        self.check_rejected('a'*32,'b'*32,'a'*32,'must be distinct')
    def test_application_password_cannot_be_sql_syntax(self):
        self.check_rejected("' OR 1=1",'b'*32,'c'*32,'hexadecimal')

    def run_users_script(self, app_grants, migrator_grants, verify_only=False):
        temporary=tempfile.TemporaryDirectory();self.addCleanup(temporary.cleanup)
        root=Path(temporary.name);log=root/'mysql.log'
        stub=root/'mysql'
        stub.write_text('#!/bin/bash\n'
                        'printf \'%s\\n\' "$*" >> "$STUB_LOG"\n'
                        'for arg in "$@"; do\n'
                        '  case "$arg" in\n'
                        '    *"SHOW GRANTS"*musealgo_app*) printf \'%s\\n\' "$STUB_APP_GRANTS"; exit 0;;\n'
                        '    *"SHOW GRANTS"*musealgo_migrator*) printf \'%s\\n\' "$STUB_MIGRATOR_GRANTS"; exit 0;;\n'
                        '  esac\n'
                        'done\n'
                        'cat > /dev/null\n'
                        'exit 0\n')
        stub.chmod(0o755)
        env=os.environ.copy();env.update(OJ_DB_PASS='a'*32,MYSQL_PASSWORD='b'*32,MYSQL_ROOT_PASSWORD='c'*32,
                                         PATH=str(root)+os.pathsep+env['PATH'],STUB_LOG=str(log),
                                         STUB_APP_GRANTS=app_grants,STUB_MIGRATOR_GRANTS=migrator_grants)
        if verify_only: env['MUSEALGO_DB_USERS_VERIFY_ONLY']='1'
        result=subprocess.run(['bash',str(Path(__file__).resolve().parents[1]/'deploy/mysql-users.sh')],
                              env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=30)
        return result, log.read_text() if log.exists() else ''
    def test_entrypoint_precreated_equivalent_grants_are_accepted(self):
        # MySQL 官方 entrypoint 用 MYSQL_USER+MYSQL_DATABASE 预建账号时先授一次 ALL，随后脚本再授一次；
        # SHOW GRANTS 会返回两行等价授权，其中一行库名转义为 oj\\_db（真实反斜杠）。
        official_app=r'GRANT SELECT, INSERT, UPDATE, DELETE ON `oj_db`.* TO `musealgo_app`@`%`'
        official_migrator=r'GRANT ALL PRIVILEGES ON `oj_db`.* TO `musealgo_migrator`@`%`'
        escaped_migrator=r'GRANT ALL PRIVILEGES ON `oj\\_db`.* TO `musealgo_migrator`@`%`'
        result,log=self.run_users_script('\n'.join(['GRANT USAGE ON *.* TO `musealgo_app`@`%`',official_app]),
                                          '\n'.join(['GRANT USAGE ON *.* TO `musealgo_migrator`@`%`',official_migrator,escaped_migrator]))
        self.assertEqual(0,result.returncode,result.stderr.decode())
        self.assertIn('Grant boundary OK for musealgo_app@%',result.stdout.decode())
        self.assertIn('Grant boundary OK for musealgo_migrator@%',result.stdout.decode())
        # 有 MYSQL_PASSWORD 环境时走了建账号分支：至少有一次不带 -e 的 heredoc 调用。
        self.assertTrue(any('SHOW GRANTS' not in call for call in log.splitlines()))
    def test_single_equivalent_grant_line_is_accepted_without_entrypoint_precreation(self):
        # 无官方 entrypoint 预建（手工/systemd 安装）时 SHOW GRANTS 只返回本脚本授出的一行。
        app=r'GRANT SELECT, INSERT, UPDATE, DELETE ON `oj_db`.* TO `musealgo_app`@`%`'
        migrator=r'GRANT ALL PRIVILEGES ON `oj_db`.* TO `musealgo_migrator`@`%`'
        result,_=self.run_users_script('\n'.join(['GRANT USAGE ON *.* TO `musealgo_app`@`%`',app]),
                                       '\n'.join(['GRANT USAGE ON *.* TO `musealgo_migrator`@`%`',migrator]))
        self.assertEqual(0,result.returncode,result.stderr.decode())
        self.assertIn('Grant boundary OK for musealgo_app@%',result.stdout.decode())
        self.assertIn('Grant boundary OK for musealgo_migrator@%',result.stdout.decode())
    def test_verify_only_mode_accepts_equivalent_grants_without_writing(self):
        official_app=r'GRANT SELECT, INSERT, UPDATE, DELETE ON `oj_db`.* TO `musealgo_app`@`%`'
        escaped_migrator=r'GRANT ALL PRIVILEGES ON `oj\\_db`.* TO `musealgo_migrator`@`%`'
        result,log=self.run_users_script('\n'.join(['GRANT USAGE ON *.* TO `musealgo_app`@`%`',official_app]),
                                          '\n'.join(['GRANT USAGE ON *.* TO `musealgo_migrator`@`%`',escaped_migrator]),
                                          verify_only=True)
        self.assertEqual(0,result.returncode,result.stderr.decode())
        # 校验模式只允许只读 SHOW GRANTS 调用，不得执行建账号/授权 SQL。
        self.assertTrue(log.splitlines())
        self.assertTrue(all('SHOW GRANTS' in call for call in log.splitlines()))
    def test_third_equivalent_grant_line_is_still_rejected(self):
        line=r'GRANT ALL PRIVILEGES ON `oj\\_db`.* TO `musealgo_migrator`@`%`'
        app=r'GRANT SELECT, INSERT, UPDATE, DELETE ON `oj_db`.* TO `musealgo_app`@`%`'
        result,_=self.run_users_script('\n'.join(['GRANT USAGE ON *.* TO `musealgo_app`@`%`',app]),
                                       '\n'.join(['GRANT USAGE ON *.* TO `musealgo_migrator`@`%`',line,line,line]))
        self.assertEqual(1,result.returncode)
        self.assertIn('Grant boundary mismatch',result.stderr.decode())
    def test_foreign_database_or_grant_option_is_still_rejected(self):
        app=r'GRANT SELECT, INSERT, UPDATE, DELETE ON `oj_db`.* TO `musealgo_app`@`%`'
        migrator=r'GRANT ALL PRIVILEGES ON `oj_db`.* TO `musealgo_migrator`@`%`'
        for foreign in (r'GRANT ALL PRIVILEGES ON `other\\_db`.* TO `musealgo_app`@`%`',
                        r'GRANT SELECT, INSERT, UPDATE, DELETE ON `oj_db`.* TO `musealgo_app`@`%` WITH GRANT OPTION',
                        r'GRANT PROCESS ON *.* TO `musealgo_app`@`%`'):
            with self.subTest(foreign=foreign):
                result,_=self.run_users_script('\n'.join(['GRANT USAGE ON *.* TO `musealgo_app`@`%`',app,foreign]),
                                               '\n'.join(['GRANT USAGE ON *.* TO `musealgo_migrator`@`%`',migrator]))
                self.assertEqual(1,result.returncode)
                self.assertIn('Unexpected grant',result.stderr.decode())

class UpgradeFlowTest(unittest.TestCase):
    def setUp(self):
        self.args=argparse.Namespace(env_file='.env',compose='docker-compose.prod.yml',project='isolated',apply=False,backup_root=None)
        self.operations=OPS.Operations(self.args)
        self.pending={'current':str(PREVIOUS_SCHEMA),'target':str(LATEST_SCHEMA),
                      'pending':[{'version':str(version),'description':f'migration {version}'}
                                 for version in range(PREVIOUS_SCHEMA+1,LATEST_SCHEMA+1)]}
        self.image_ids={'migrate':'sha256:'+'a'*64,'backend':'sha256:'+'a'*64,'frontend':'sha256:'+'b'*64}
        self.temporary=tempfile.TemporaryDirectory()
        self.args.backup_root=self.temporary.name

    def tearDown(self):
        self.temporary.cleanup()

    def patch_preflight(self, status_effect):
        return (patch.object(self.operations,'resolve_image_ids',return_value=self.image_ids),
                patch.object(self.operations,'_migration_status',return_value=status_effect) if isinstance(status_effect,dict)
                else patch.object(self.operations,'_migration_status',side_effect=status_effect))

    def test_preview_does_not_stop_backup_or_migrate(self):
        resolve,status=self.patch_preflight(self.pending)
        with resolve,status,patch.object(self.operations,'backup_to') as backup,patch.object(self.operations,'compose') as compose:
            self.operations.upgrade()
        backup.assert_not_called();compose.assert_not_called()

    def test_current_database_is_a_noop(self):
        self.args.apply=True
        current={'current':str(LATEST_SCHEMA),'target':str(LATEST_SCHEMA),'pending':[]}
        resolve,status=self.patch_preflight(current)
        with resolve,status,patch.object(self.operations,'backup_to') as backup,patch.object(self.operations,'compose') as compose:
            self.operations.upgrade()
        backup.assert_not_called();compose.assert_not_called()

    def test_apply_verifies_backup_before_migration_and_starts_only_after_validation(self):
        self.args.apply=True; events=[]
        resolve,status=self.patch_preflight([self.pending,self.pending,
                                             {'current':str(LATEST_SCHEMA),'target':str(LATEST_SCHEMA),'pending':[]}])
        with resolve,status,patch.object(self.operations,'backup_to',side_effect=lambda *a,**kw: events.append(('backup',kw))),patch.object(self.operations,'verify',side_effect=lambda *a: events.append(('verify',None))),patch.object(self.operations,'compose',side_effect=lambda *a,**kw: events.append(('compose',a))):
            self.operations.upgrade()
        self.assertEqual(['backup','verify','compose','compose'],[e[0] for e in events])
        self.assertEqual({'resume':False,'image_ids':self.image_ids},events[0][1])
        self.assertEqual(('run','--pull','never','--rm','--no-deps','migrate'),events[2][1])
        self.assertIn('--wait',events[3][1])
        self.assertIn('--pull',events[3][1])

    def test_failed_migration_never_starts_application(self):
        self.args.apply=True
        def command(*args,**kw):
            if args[0]=='run': raise subprocess.CalledProcessError(1,'synthetic failure')
        resolve,status=self.patch_preflight([self.pending,self.pending])
        with resolve,status,patch.object(self.operations,'backup_to'),patch.object(self.operations,'verify'),patch.object(self.operations,'state',return_value=[]),patch.object(self.operations,'compose',side_effect=command) as compose:
            with self.assertRaisesRegex(ValueError,'数据库升级.*已请求业务停写.*已校验备份位置'):
                self.operations.upgrade()
        self.assertFalse(any(c.args[0]=='up' for c in compose.call_args_list))
        self.assertEqual('stop',compose.call_args.args[0])

    def test_invalid_history_stops_before_any_backup_or_write(self):
        self.args.apply=True
        resolve,status=self.patch_preflight(ValueError('checksum mismatch'))
        with resolve,status,patch.object(self.operations,'backup_to') as backup,patch.object(self.operations,'compose') as compose:
            with self.assertRaisesRegex(ValueError,'checksum'): self.operations.upgrade()
        backup.assert_not_called();compose.assert_not_called()

    def test_changed_database_preflight_refuses_to_stop_or_backup(self):
        self.args.apply=True
        changed={'current':str(PREVIOUS_SCHEMA-1),'target':str(LATEST_SCHEMA),'pending':self.pending['pending']}
        resolve,status=self.patch_preflight([self.pending,changed])
        with resolve,status,patch.object(self.operations,'backup_to') as backup,patch.object(self.operations,'compose') as compose:
            with self.assertRaisesRegex(ValueError,'数据库状态.*未停写'):
                self.operations.upgrade()
        backup.assert_not_called();compose.assert_not_called()

    def test_corrupt_backup_prevents_migration_and_reports_unusable_directory(self):
        self.args.apply=True
        resolve,status=self.patch_preflight([self.pending,self.pending])
        with resolve,status,patch.object(self.operations,'backup_to'),patch.object(self.operations,'verify',side_effect=ValueError('checksum mismatch')),patch.object(self.operations,'state',return_value=[]),patch.object(self.operations,'compose') as compose:
            with self.assertRaisesRegex(ValueError,'备份未通过校验.*不可直接用于恢复'):
                self.operations.upgrade()
        self.assertFalse(any(call.args[0]=='run' for call in compose.call_args_list))
        self.assertTrue(any(call.args[0]=='stop' for call in compose.call_args_list))

    def test_apply_binds_one_image_identity_for_all_compose_mutations(self):
        self.args.apply=True
        resolve,status=self.patch_preflight([self.pending,self.pending,
                                             {'current':str(LATEST_SCHEMA),'target':str(LATEST_SCHEMA),'pending':[]}])
        observations=[]
        def record_compose(*args,**kwargs):
            observations.append((args,self.operations.env.get('BACKEND_IMAGE'),self.operations.env.get('FRONTEND_IMAGE')))
            return subprocess.CompletedProcess([],0,stdout=b'')
        def record_backup(*args,**kwargs):
            self.assertEqual(self.image_ids['backend'],self.operations.env['BACKEND_IMAGE'])
            self.assertEqual(self.image_ids['frontend'],self.operations.env['FRONTEND_IMAGE'])
        with resolve,status,patch.object(self.operations,'backup_to',side_effect=record_backup),patch.object(self.operations,'verify'),patch.object(self.operations,'compose',side_effect=record_compose):
            self.operations.upgrade()
        self.assertTrue(observations)
        self.assertTrue(all(backend==self.image_ids['backend'] and frontend==self.image_ids['frontend']
                            for _,backend,frontend in observations))

    def test_status_requires_new_readonly_entrypoint_instead_of_legacy_runner(self):
        payload=json.dumps({'current':str(PREVIOUS_SCHEMA),'target':str(LATEST_SCHEMA),'pending':[]})
        response=subprocess.CompletedProcess([],0,stdout=f'MUSEALGO_DATABASE_STATUS={payload}\n'.encode())
        ids={'migrate':'sha256:'+'a'*64,'backend':'sha256:'+'a'*64,'frontend':'sha256:'+'b'*64}
        with patch.object(self.operations,'resolve_image_ids',return_value=ids),patch.object(self.operations,'compose',return_value=response) as compose:
            self.assertEqual(str(PREVIOUS_SCHEMA),self.operations.migration_status()['current'])
        self.assertIn('-Dloader.main=com.musealgo.oj.DatabaseMaintenance',compose.call_args.args)
        self.assertEqual('status',compose.call_args.args[-1])

class SimpleEntryTest(unittest.TestCase):
    def test_help_exposes_status_and_upgrade_without_version_arguments(self):
        result=subprocess.run(['python3',str(OPS.ROOT/'scripts/ops.py'),'--help'],capture_output=True,text=True)
        self.assertEqual(0,result.returncode)
        self.assertIn('status',result.stdout)
        self.assertIn('upgrade',result.stdout)

class ImageIdentityTest(unittest.TestCase):
    def setUp(self):
        args=argparse.Namespace(env_file='.env',compose='docker-compose.prod.yml',project='isolated')
        self.operations=OPS.Operations(args)

    def test_resolves_and_rejects_split_migration_and_runtime_images(self):
        config={'services':{'migrate':{'image':'oj/backend:latest'},
                            'backend':{'image':'oj/backend:latest'},
                            'frontend':{'image':'oj/frontend:latest'}}}
        ids={'oj/backend:latest':'sha256:'+'a'*64,'oj/frontend:latest':'sha256:'+'b'*64}
        def inspect(command,**kwargs):
            return subprocess.CompletedProcess(command,0,stdout=ids[command[-1]]+'\n',stderr='')
        with patch.object(self.operations,'compose',return_value=subprocess.CompletedProcess([],0,stdout=json.dumps(config).encode())),patch.object(OPS.subprocess,'run',side_effect=inspect):
            result=self.operations.resolve_image_ids()
        self.assertEqual(ids['oj/backend:latest'],result['migrate'])
        self.assertEqual(ids['oj/backend:latest'],result['backend'])
        self.assertEqual(ids['oj/frontend:latest'],result['frontend'])

    def test_rejects_when_migration_and_runtime_tags_resolve_to_different_ids(self):
        config={'services':{'migrate':{'image':'oj/backend:migrate'},
                            'backend':{'image':'oj/backend:runtime'},
                            'frontend':{'image':'oj/frontend:latest'}}}
        values={'oj/backend:migrate':'a','oj/backend:runtime':'b','oj/frontend:latest':'c'}
        def inspect(command,**kwargs):
            return subprocess.CompletedProcess(command,0,stdout='sha256:'+values[command[-1]]*64+'\n',stderr='')
        with patch.object(self.operations,'compose',return_value=subprocess.CompletedProcess([],0,stdout=json.dumps(config).encode())),patch.object(OPS.subprocess,'run',side_effect=inspect):
            with self.assertRaisesRegex(ValueError,'不同镜像'):
                self.operations.resolve_image_ids()

    def test_temporary_image_binding_is_restored_after_error(self):
        original=self.operations.env.get('BACKEND_IMAGE')
        ids={'migrate':'sha256:'+'a'*64,'backend':'sha256:'+'a'*64,'frontend':'sha256:'+'b'*64}
        previous_command=list(self.operations.cmd)
        with self.assertRaisesRegex(RuntimeError,'expected'):
            with self.operations.use_image_ids(ids):
                self.assertEqual(ids['backend'],self.operations.env['BACKEND_IMAGE'])
                raise RuntimeError('expected')
        self.assertEqual(original,self.operations.env.get('BACKEND_IMAGE'))
        self.assertEqual(previous_command,self.operations.cmd)

    def test_compose_override_pins_hardcoded_images_for_custom_compose_files(self):
        ids={'migrate':'sha256:'+'a'*64,'backend':'sha256:'+'a'*64,'frontend':'sha256:'+'b'*64}
        captured=[]
        with tempfile.TemporaryDirectory() as temp:
            compose_path=Path(temp)/'custom-compose.yml'
            compose_path.write_text('services:\n  migrate:\n    image: custom/backend:latest\n'
                                    '  backend:\n    image: custom/backend:latest\n'
                                    '  frontend:\n    image: custom/frontend:latest\n')
            args=argparse.Namespace(env_file='.env',compose=str(compose_path),project='custom')
            operations=OPS.Operations(args)
            previous_command=list(operations.cmd)
        def run_compose(*args,**kwargs):
            command=operations.cmd
            override_path=Path(command[command.index('-f',command.index('-f')+1)+1])
            captured.append((command.copy(),json.loads(override_path.read_text()),
                             override_path,override_path.stat().st_mode & 0o777))
            return subprocess.CompletedProcess(command,0,stdout=b'')
        with patch.object(operations,'compose',side_effect=run_compose):
            with operations.use_image_ids(ids):
                operations.compose('config','--format','json')
        command,override,override_path,override_mode=captured[0]
        self.assertEqual({'migrate':{'image':ids['migrate']},'backend':{'image':ids['backend']},
                          'frontend':{'image':ids['frontend']}},override['services'])
        self.assertEqual(0o600,override_mode)
        self.assertFalse(override_path.exists())
        self.assertEqual('-f',command[-2])
        self.assertEqual(str(override_path),command[-1])
        self.assertEqual(previous_command,operations.cmd)

    def test_maintenance_lock_rejects_a_second_local_writer_and_releases_after_error(self):
        with tempfile.TemporaryDirectory() as temp:
            with patch.object(OPS,'ROOT',Path(temp)/'repo'):
                with self.operations.maintenance_lock():
                    with self.assertRaisesRegex(ValueError,'已有维护操作'):
                        with self.operations.maintenance_lock():
                            self.fail('nested lock unexpectedly acquired')
                with self.operations.maintenance_lock():
                    pass

if __name__=='__main__':
    unittest.main()
