import argparse
import importlib.util
import json
import os
import subprocess
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

SPEC=importlib.util.spec_from_file_location('musealgo_ops',Path(__file__).resolve().parents[1]/'scripts/ops.py')
OPS=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(OPS)


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
        with patch.object(self.operations,'state',return_value=[]),patch.object(self.operations,'compose') as compose:
            with patch.object(OPS.subprocess,'run') as run:
                run.return_value.stdout=b'existing-volume\n'
                with self.assertRaisesRegex(ValueError,'already has volumes'):
                    self.operations.restore()
            compose.assert_not_called()
    def test_corrupt_backup_is_rejected_before_docker_access(self):
        (self.root/'database.sql').write_text('modified')
        with patch.object(self.operations,'compose') as compose,patch.object(self.operations,'state') as state:
            with self.assertRaisesRegex(ValueError,'checksum mismatch'):
                self.operations.restore()
            compose.assert_not_called();state.assert_not_called()

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

if __name__=='__main__':
    unittest.main()
